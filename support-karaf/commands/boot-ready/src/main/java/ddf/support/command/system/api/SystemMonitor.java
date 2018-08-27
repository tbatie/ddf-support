package ddf.support.command.system.api;

import java.util.Map;

import org.apache.karaf.features.FeatureState;
import org.osgi.service.cm.Configuration;

public interface SystemMonitor {

  /**
   * Creates a Managed Service that is created from a Managed Service Factory. Waits for the
   * asynchronous call that the properties have been updated and the service can be used.
   *
   * <p>For Managed Services not created from a Managed Service Factory, use {@link
   * #updateManagedService(String, Map)} instead.
   *
   * @param factoryPid the factory pid of the Managed Service Factory
   * @param properties the service properties for the Managed Service
   */
  Configuration createManagedFactoryService(String factoryPid, Map<String, Object> properties)
          throws SystemMonitorException;

  Configuration createManagedFactoryService(long maxWaitTime, String factoryPid,
          Map<String, Object> properties) throws SystemMonitorException;

  /**
   * Wait for Managed Service to come up and then updates the service. Waits for the asynchronous call that the properties have been updated
   * and the service can be used.
   *
   * <p>For services created from a Managed Service Factory, use {@link
   * #createManagedFactoryService(String, Map)} instead.
   *
   * @param servicePid persistent identifier of the Managed Service to start
   * @param properties service configuration properties
   */
  void updateManagedService(String servicePid, Map<String, Object> properties)
          throws SystemMonitorException;

  void updateManagedService(long maxWaitTime, String factoryPid, Map<String, Object> properties)
          throws SystemMonitorException;

  /**
   * Waits for the asynchronous call that the properties have been updated and the service can be used.
   *
   * @param servicePid pid of service to wait for
   */
  void waitForServiceAvailability(String servicePid) throws SystemMonitorException;

  void waitForServiceAvailability(long maxWaitTime, String servicePid)
          throws SystemMonitorException;

  /**
   * Installs and starts one or more features. Waits until the state of all specified features are
   * {@code Started} and all bundles are {@code Active} before returning.
   *
   * @param feature            name of feature to install & start
   * @param additionalFeatures names of additional features to install & start
   */
  void installFeatures(String feature, String... additionalFeatures) throws SystemMonitorException;

  void installFeatures(long maxWaitTime, String feature, String... additionalFeatures)
          throws SystemMonitorException;

  /**
   * Uninstalls one or more features. Waits for the all bundles to reach an {@code Active} state before returning
   *
   * @param feature            name of feature to install & start
   * @param additionalFeatures names of additional features to install & start
   */
  void uninstallFeatures(String feature, String... additionalFeatures)
          throws SystemMonitorException;

  void uninstallFeatures(long maxWaitTime, String feature, String... additionalFeatures)
          throws SystemMonitorException;

  /**
   * Waits until the state of all specified features are
   * {@code Started} and all bundles are {@code Active} before returning.
   *
   * @param feature            name of feature to install & start
   * @param additionalFeatures names of additional features to install & start
   */
  void waitForFeatures(FeatureState expectedState, String feature, String... additionalFeatures)
          throws SystemMonitorException;

  void waitForFeatures(long maxWaitTime, FeatureState expectedState, String feature,
          String... additionalFeatures) throws SystemMonitorException;

  // TODO: tbatie - 8/23/18 - Should we remove the start/stop bundles? There are no waits being performed in either of these and bundles are capable of performing this operation
  /**
   * Stops the specified bundle. Waits until the bundle reaches a resolved state before returning.
   *
   * @param symbolicName symbolic name of installed bundle to stop
   */
  void stopBundles(String symbolicName, String... additionalSymbolicNames)
          throws SystemMonitorException;

  /**
   * Starts the specified bundles. Waits until all bundles are {@code Active} before returning.
   *
   * @param symbolicName symbolic name of installed bundle to start
   */
  void startBundles(String symbolicName, String... additionalSymbolicNames)
          throws SystemMonitorException;

  /**
   * Waits for installed bundles with the specified symbolic names to be in an {@code Active} before returning.
   * If no symbolicNames are specified, will wait for all installed bundles to reach an {@code Active} state before returning.
   *
   * @param symbolicNames symbolic name of installed bundle to start
   */
  void waitForBundles(String... symbolicNames) throws SystemMonitorException;

  void waitForBundles(long maxWaitTime, String... symbolicNames) throws SystemMonitorException;

}
