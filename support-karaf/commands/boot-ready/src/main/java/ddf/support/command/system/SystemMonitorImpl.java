package ddf.support.command.system;

import static org.osgi.framework.Constants.SERVICE_PID;
import static org.osgi.service.cm.ConfigurationAdmin.SERVICE_FACTORYPID;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Dictionary;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.karaf.bundle.core.BundleInfo;
import org.apache.karaf.bundle.core.BundleService;
import org.apache.karaf.bundle.core.BundleState;
import org.apache.karaf.features.Feature;
import org.apache.karaf.features.FeatureState;
import org.apache.karaf.features.FeaturesService;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.cm.ConfigurationEvent;
import org.osgi.service.cm.ConfigurationListener;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.cm.ManagedServiceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;

import ddf.support.command.system.api.SystemMonitor;

public class SystemMonitorImpl implements SystemMonitor {

  // TODO: tbatie - 8/20/18 - Fix security manager
  private static final Map<Integer, String> BUNDLE_STATES =
          new ImmutableMap.Builder<Integer, String>().put(Bundle.UNINSTALLED, "UNINSTALLED")
                  .put(Bundle.INSTALLED, "INSTALLED")
                  .put(Bundle.RESOLVED, "RESOLVED")
                  .put(Bundle.STARTING, "STARTING")
                  .put(Bundle.STOPPING, "STOPPING")
                  .put(Bundle.ACTIVE, "ACTIVE")
                  .build();

  private static final String ALL_SERVICES_LDAP_FILTER = "(" + SERVICE_PID + "=*)";

  private static final String ALL_SERVICE_FACTORIES_LDAP_FILTER = "(" + SERVICE_FACTORYPID + "=*)";

  private static final long DEFAULT_MAX_FEATURE_WAIT = TimeUnit.MINUTES.toMillis(10);

  private static final long DEFAULT_MAX_SERVICE_WAIT = TimeUnit.MINUTES.toMillis(10);

  private static final long DEFAULT_MAX_BUNDLE_WAIT = TimeUnit.MINUTES.toMillis(10);

  private static final Logger LOGGER = LoggerFactory.getLogger(SystemMonitorImpl.class);

  private BundleContext bundleContext;

  private ConfigurationAdmin configAdmin;

  private FeaturesService featuresService;

  private BundleService bundleService;

  public SystemMonitorImpl(BundleContext bundleContext, ConfigurationAdmin configAdmin,
          FeaturesService featuresService, BundleService bundleService) {
    this.bundleContext = bundleContext;
    this.configAdmin = configAdmin;
    this.featuresService = featuresService;
    this.bundleService = bundleService;

  }

  @Override
  public Configuration createManagedFactoryService(String factoryPid,
          Map<String, Object> properties) throws SystemMonitorException {
    return createManagedFactoryService(DEFAULT_MAX_SERVICE_WAIT, factoryPid, properties);
  }

  @Override
  public Configuration createManagedFactoryService(long maxWaitTime, String factoryPid,
          Map<String, Object> properties) throws SystemMonitorException {
    LOGGER.debug("Creating managed service of factorypid [{}]", factoryPid);

    Configuration createdConfig;
    try {
      createdConfig = configAdmin.createFactoryConfiguration(factoryPid, null);
    } catch (IOException e) {
      throw new SystemMonitorException(
              "Failed to initialize managed service factory configuration with pid of: "
                      + factoryPid,
              e);
    }

    LOGGER.debug("Created factory configuration with pid of [{}]. Now updating configuration with properties.", createdConfig.getPid());

    try {
      createdConfig.update(new Hashtable<>(properties));
    } catch (IOException e) {
      throw new SystemMonitorException(
              "Failed to updated created managed service factory configuration with pid of ["
                      + factoryPid + "]",
              e);
    }
    // TODO: tbatie - 8/17/18 - should we try to delete managed service factory that couldn't update?

    LOGGER.debug("Updated factory configuration with pid of [{}].", createdConfig.getPid());
    waitForServiceAvailability(maxWaitTime, factoryPid);
    return createdConfig;
  }

  @Override
  public void updateManagedService(String servicePid, Map<String, Object> properties)
          throws SystemMonitorException {
    updateManagedService(DEFAULT_MAX_SERVICE_WAIT, servicePid, properties);
  }

  @Override
  public void updateManagedService(long maxWaitTime, String servicePid, Map<String, Object> properties)
          throws SystemMonitorException {
    waitForServiceAvailability(maxWaitTime, servicePid);

    ServiceConfigurationListener serviceListener = new ServiceConfigurationListener(servicePid);
    ServiceRegistration<ConfigurationListener> serviceListenerRef = bundleContext.registerService(
            ConfigurationListener.class,
            serviceListener,
            null);

    LOGGER.debug("Updating configuration of service with pid [{}].", servicePid);


    try {
      configAdmin.getConfiguration(servicePid)
              .update(new Hashtable<>(properties));
    } catch (Exception e) {
      throw new SystemMonitorException(
              "Failed to update managed service configuration with pid of [" + servicePid + "]",
              e);
    } finally {
      serviceListenerRef.unregister();
    }

    LOGGER.debug("Updated configuration of service with pid [{}].", servicePid);

    boolean available;
    Callable<Boolean> isServiceUpdated = () -> {
      LOGGER.info("Waiting for service with pid [{}] to reflect configuration updates...", servicePid);
      return !serviceListener.isUpdated();
    };

    try {
      available = wait(isServiceUpdated, maxWaitTime, 5);
    } catch (Exception e) {
      throw new SystemMonitorException(
              "Interrupted while waiting for service with pid of [" + servicePid
                      + "] to be updated.", e);
    } finally {
      serviceListenerRef.unregister();
    }

    if (!available) {
      throw new SystemMonitorException(
              "Managed service failed to update within " + DEFAULT_MAX_SERVICE_WAIT + " ms.");
    }
  }

  @Override
  public void waitForServiceAvailability(String servicePid) throws SystemMonitorException {
    waitForServiceAvailability(DEFAULT_MAX_SERVICE_WAIT, servicePid);
  }

  @Override
  public void waitForServiceAvailability(long maxWaitTime, String servicePid)
          throws SystemMonitorException {
    Collection<ServiceReference<ManagedService>> serviceRefs;
    Collection<ServiceReference<ManagedServiceFactory>> serviceFactoryRefs;

    try {
      serviceRefs = bundleContext.getServiceReferences(ManagedService.class,
              ALL_SERVICES_LDAP_FILTER);
    } catch (InvalidSyntaxException e) {
      throw new SystemMonitorException("Failed to retrieve managed services from system.", e);
    }

    try {
      serviceFactoryRefs = bundleContext.getServiceReferences(ManagedServiceFactory.class,
              ALL_SERVICE_FACTORIES_LDAP_FILTER);
    } catch (InvalidSyntaxException e) {
      throw new SystemMonitorException("Failed to retrieve managed service factories from system.",
              e);
    }

    Callable<Boolean> isServiceAvailable = () -> {
      LOGGER.info("Waiting for service with pid [{}] to be registered", servicePid);

      return serviceRefs.stream()
              .anyMatch(s -> servicePid.equals(s.getProperty(SERVICE_PID))) || serviceFactoryRefs.stream()
              .anyMatch(s -> servicePid.equals(s.getProperty(SERVICE_FACTORYPID)));
    };

    boolean available;
    try {
      available = wait(isServiceAvailable, maxWaitTime, 5);
    } catch (Exception e) {
      throw new SystemMonitorException(
              "Interrupted while waiting for service with pid of [" + servicePid
                      + "] to become available.", e);
    }

    if (!available) {
      throw new SystemMonitorException(
              "Managed service failed to appear after " + DEFAULT_MAX_SERVICE_WAIT + " ms.");
    }
  }

  @Override
  public void installFeatures(String feature, String... additionalFeatures)
          throws SystemMonitorException {
    installFeatures(DEFAULT_MAX_FEATURE_WAIT, feature, additionalFeatures);
  }

  @Override
  public void installFeatures(long maxWaitTime, String feature, String... additionalFeatures)
          throws SystemMonitorException {

    Set<String> featuresToInstall = getFeatures(feature,
            additionalFeatures).stream().filter(f -> !featuresService.isInstalled(f))
            .map(Feature::getName)
            .collect(Collectors.toSet());

    LOGGER.debug("Installing the following features: [{}]", String.join(", ", featuresToInstall));

    try {
      featuresService.installFeatures(featuresToInstall,
              EnumSet.of(FeaturesService.Option.NoAutoRefreshBundles));
    } catch (Exception e) {
      throw new SystemMonitorException(
              "Failed to install features [" + String.join(",", featuresToInstall) + "]", e);
    }

    LOGGER.debug("Finished installing features.");

    waitForFeatures(maxWaitTime, FeatureState.Started, feature, additionalFeatures);
  }

  @Override
  public void uninstallFeatures(String feature, String... additionalFeatures)
          throws SystemMonitorException {
    uninstallFeatures(DEFAULT_MAX_FEATURE_WAIT, feature, additionalFeatures);
  }

  @Override
  public void uninstallFeatures(long maxWaitTime, String feature, String... additionalFeatures)
          throws SystemMonitorException {
    Set<String> featuresToUninstall = getFeatures(feature, additionalFeatures).stream().filter(f -> !featuresService.isInstalled(f))
            .map(Feature::getName)
            .collect(Collectors.toSet());

    try {
      featuresService.uninstallFeatures(featuresToUninstall,
              EnumSet.of(FeaturesService.Option.NoAutoRefreshBundles));
    } catch (Exception e) {
      throw new SystemMonitorException(
              "Failed to uninstall features [" + String.join(",", featuresToUninstall) + "]", e);
    }

    waitForFeatures(maxWaitTime, FeatureState.Uninstalled, feature, additionalFeatures);
  }

  @Override
  public void waitForFeatures(FeatureState expectedState, String feature, String... additionalFeatures)
          throws SystemMonitorException {
    waitForFeatures(DEFAULT_MAX_FEATURE_WAIT, expectedState, feature, additionalFeatures);
  }

  @Override
  public void waitForFeatures(long maxWaitTime, FeatureState expectedState, String feature, String... additionalFeatures)
          throws SystemMonitorException {
    waitForFeatures(maxWaitTime, expectedState, getFeatures(feature, additionalFeatures));
  }

  private void waitForFeatures(long maxWaitTime, FeatureState expectedState, List<Feature> features) throws SystemMonitorException {

    Callable<Boolean> areFeaturesDone = () -> {
      List<Feature> pendingFeatures = features.stream().filter(f ->
              featuresService.getState(f.getName() + "/" + f.getVersion()) != expectedState).collect(
              Collectors.toList());

      if (!pendingFeatures.isEmpty()) {
        LOGGER.info("Waiting for features [{}] to reach expected state of [{}].",
                featuresAsString(pendingFeatures),
                expectedState.name());
        return false;
      } else {
        return true;
      }
    };

    boolean available;
    try {
      available = wait(areFeaturesDone, maxWaitTime, 5);
    } catch (Exception e) {
      throw new SystemMonitorException(
              "Interrupted while waiting for features [" + featuresAsString(features) + "] to become available.", e);
    }

    if (!available) {
      throw new SystemMonitorException(
              "Features failed to reach state [" + expectedState.name() + "] within " + DEFAULT_MAX_SERVICE_WAIT
                      + " ms.");
    }

    waitForBundles();
  }

  @Override
  public void stopBundles(String symbolicName, String... additionalSymbolicNames)
          throws SystemMonitorException {
    Set<String> toStop = toSet(symbolicName, additionalSymbolicNames);

    for (Bundle bundle : bundleContext.getBundles()) {
      if (toStop.contains(bundle.getSymbolicName())) {
        try {
          bundle.stop();
        } catch (BundleException e) {
          throw new SystemMonitorException("Failed to stop bundle [" + bundle.getSymbolicName() + "]");
        }
      }
    }
  }

  @Override
  public void startBundles(String symbolicName, String... additionalSymbolicNames)
          throws SystemMonitorException {
    Set<String> toStart = toSet(symbolicName, additionalSymbolicNames);

    for (Bundle bundle : bundleContext.getBundles()) {
      if (toStart.contains(bundle.getSymbolicName())) {
        try {
          bundle.start();
        } catch (BundleException e) {
          throw new SystemMonitorException("Failed to start bundle [" + bundle.getSymbolicName() + "]");
        }
      }
    }
  }


  @Override
  public void waitForBundles(String... symbolicNames) throws SystemMonitorException {
    waitForBundles(DEFAULT_MAX_BUNDLE_WAIT, symbolicNames);
  }

  @Override
  public void waitForBundles(long maxWaitTime, String... symbolicNames) throws SystemMonitorException {
    Set<String> toWaitFor = symbolicNames.length == 0 ?
            Arrays.stream(bundleContext.getBundles())
                    .map(Bundle::getSymbolicName)
                    .collect(Collectors.toSet()) :
            Arrays.stream(symbolicNames)
                    .collect(Collectors.toSet());

    Callable<Boolean> areBundlesReady = () -> getUnavailableBundles(toWaitFor).isEmpty();

    LOGGER.debug("Waiting for bundles to become available.");
    boolean available;

    try {
      available = wait(areBundlesReady, maxWaitTime, TimeUnit.SECONDS.toMillis(5));
    } catch(SystemMonitorException e) {
      throw e;
    } catch (Exception e) {
      throw new SystemMonitorException("Interrupted while waiting for bundles to reach active state.", e);
    }

    if (!available) {
      printInactiveBundles(LOGGER::error, LOGGER::error);
      throw new SystemMonitorException("Bundles failed to reach Active within: "  + maxWaitTime + " ms.");
    }
  }

  private Set<Bundle> getUnavailableBundles(Set<String> toCheck) throws SystemMonitorException {
    Set<Bundle> unavailableBundles = new HashSet<>();

    for (Bundle bundle : bundleContext.getBundles()) {
      if (!toCheck.contains(bundle.getSymbolicName())) {
        continue;
      }

      String bundleName = bundle.getHeaders()
              .get(Constants.BUNDLE_NAME);
      BundleInfo bundleInfo = bundleService.getInfo(bundle);
      BundleState bundleState = bundleInfo.getState();
      if (bundleInfo.isFragment()) {
        if (!BundleState.Resolved.equals(bundleState)) {
          LOGGER.debug("Fragment [" + bundleName + "] not ready with state [{}]",
                  bundleName,
                  bundleState);
          unavailableBundles.add(bundle);
        }
      } else {
        if (BundleState.Failure.equals(bundleState)) {
          printInactiveBundles(LOGGER::error, LOGGER::error);
          throw new SystemMonitorException("Bundle [" + bundleName + "] failed to start up.");
        } else if (!BundleState.Active.equals(bundleState)) {
          unavailableBundles.add(bundle);
          LOGGER.debug("Bundle [{}] not ready with state [{}]", bundleName, bundleState);
        }
      }
    }
    return unavailableBundles;
  }

  @VisibleForTesting
  protected boolean wait(Callable<Boolean> conditionIsMet, long maxWait, long pollInterval) throws Exception {
    final long startTime = System.currentTimeMillis();
    LOGGER.trace("Waiting for condition to be met. Max wait time: [{}]" + (startTime + maxWait));

    // TODO: tbatie - 8/23/18 - Remove souts
    do {
      if (conditionIsMet.call()) {
        LOGGER.trace("Waiting condition met. Waited for [{}] ms" + (System.currentTimeMillis() - startTime));

        return true;
      } else {
        LOGGER.trace("Waiting condition not met. Sleeping for [{}]", pollInterval);
        Thread.sleep(pollInterval);
      }
    } while ((System.currentTimeMillis() - startTime) <= maxWait);

    LOGGER.trace("Condition not met within [{}]", maxWait);
    return false;
  }

  // TODO: tbatie - 8/17/18 - Finish going through this
  private void printInactiveBundles(Consumer<String> headerConsumer,
          BiConsumer<String, Object[]> logConsumer) {
    headerConsumer.accept("Listing inactive bundles");

    for (Bundle bundle : bundleContext.getBundles()) {
      if (bundle.getState() != Bundle.ACTIVE) {
        Dictionary<String, String> headers = bundle.getHeaders();
        if (headers.get("Fragment-Host") != null) {
          continue;
        }

        StringBuilder headerString = new StringBuilder("[ ");
        Enumeration<String> keys = headers.keys();

        while (keys.hasMoreElements()) {
          String key = keys.nextElement();
          headerString.append(key)
                  .append("=")
                  .append(headers.get(key))
                  .append(", ");
        }

        headerString.append(" ]");
        logConsumer.accept("\n\tBundle: {}_v{} | {}\n\tHeaders: {}",
                new Object[] {bundle.getSymbolicName(), bundle.getVersion(),
                        BUNDLE_STATES.getOrDefault(bundle.getState(), "UNKNOWN"), headerString});
      }
    }
  }

  private List<Feature> getFeatures(String feature, String... additionalFeatures) throws SystemMonitorException{
    Stream.Builder<Feature> transformedFeatures = Stream.builder();

    for(String f : toList(feature, additionalFeatures)) {
      try {
        transformedFeatures.add(featuresService.getFeature(f));
      } catch (Exception e) {
        throw new SystemMonitorException("Failed to retrieve feature [" + f + "]", e);
      }
    }

    return transformedFeatures.build().collect(Collectors.toList());
  }

  private String featuresAsString(List<Feature> features) {
    return features.stream().map(Feature::getName)
            .collect(Collectors.joining(","));
  }

  private <T> List<T> toList(T ele, T... eles) {
    return Stream.concat(Stream.of(ele), Stream.of(eles)).collect(Collectors.toList());
  }

  private <T> Set<T> toSet(T ele, T... eles) {
    return Stream.concat(Stream.of(ele), Stream.of(eles)).collect(Collectors.toSet());
  }

  private class ServiceConfigurationListener implements ConfigurationListener {

    private final String pid;

    private boolean updated;

    public ServiceConfigurationListener(String pid) {
      this.pid = pid;
    }

    @Override
    public void configurationEvent(ConfigurationEvent event) {
      if (event.getPid()
              .equals(pid) && ConfigurationEvent.CM_UPDATED == event.getType()) {
        updated = true;
      }
    }

    public boolean isUpdated() {
      return updated;
    }
  }

}
