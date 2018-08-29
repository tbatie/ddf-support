package ddf.support.command.system

import ddf.support.command.system.api.SystemMonitorException
import org.apache.karaf.bundle.core.BundleService
import org.apache.karaf.features.FeaturesService
import org.osgi.framework.BundleContext
import org.osgi.framework.Constants
import org.osgi.framework.InvalidSyntaxException
import org.osgi.framework.ServiceReference
import org.osgi.service.cm.Configuration
import org.osgi.service.cm.ConfigurationAdmin
import org.osgi.service.cm.ManagedService
import org.osgi.service.cm.ManagedServiceFactory
import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit


class SystemMonitorSpec extends Specification {

    private BundleContext bundleContext
    private ConfigurationAdmin configAdmin
    private FeaturesService featuresService
    private BundleService bundleService
    private ServiceReference serviceReference


    private SystemMonitorImpl systemMonitor

    def setup() {
        def implementedService = Mock(ServiceReference)
        implementedService.getProperty('service.pid') >> 'xxx'

        bundleContext = Mock(BundleContext)
        configAdmin = Mock(ConfigurationAdmin)
        featuresService = Mock(FeaturesService)
        bundleService = Mock(BundleService)
        serviceReference = Mock(ServiceReference)
    }

    def 'test wait method when condition is met on first try.'() {
        setup:
        def numberTimesCalled = 0
        def conditionToMeet = new Callable<Boolean>() {
            @Override
            Boolean call() throws Exception {
                numberTimesCalled++
                return true
            }
        }

        when:
        def result = new SystemMonitorImpl(null, null, null, null).wait(conditionToMeet, 1, 1)

        then:
        numberTimesCalled == 1
        result
    }

    def 'wait condition is met later on'() {
        setup:
        def expectedTimesCalled = 3
        def numberTimesCalled = 0
        def conditionToMeet = new Callable<Boolean>() {
            @Override
            Boolean call() throws Exception {
                numberTimesCalled++
                return numberTimesCalled == expectedTimesCalled;
            }
        }

        when:
        def result = new SystemMonitorImpl(null, null, null, null).wait(conditionToMeet, TimeUnit.SECONDS.toMillis(15), 1)

        then:
        result
        numberTimesCalled == expectedTimesCalled
    }

    def 'wait condition is never met'() {
        setup:
        def conditionToMeet = new Callable<Boolean>() {
            @Override
            Boolean call() throws Exception {
                return false
            }
        }

        when:
        def result = new SystemMonitorImpl(null, null, null, null).wait(conditionToMeet, TimeUnit.SECONDS.toMillis(1), TimeUnit.SECONDS.toMillis(1))

        then:
        !result
    }

    def 'poll interval is longer than max wait'() {
        setup:
        def maxWait = TimeUnit.SECONDS.toMillis(1)
        def pollInterval = TimeUnit.SECONDS.toMillis(2)
        def conditionToMeet = new Callable<Boolean>() {
            @Override
            Boolean call() throws Exception {
                return false
            }
        }

        when:
        def result = new SystemMonitorImpl(null, null, null, null).wait(conditionToMeet, maxWait, pollInterval)

        then:
        !result
    }

    def 'managed service factory successfully created'() {
        setup:

        def returnedConfig
        def createdPid = "createdPid"
        def createdProps = ['propKey': 'propValue']
        def createdConfig = Mock(Configuration)
        createdConfig.getPid() >> createdPid

        configAdmin.createFactoryConfiguration(_, _) >> createdConfig

        bundleContext.getServiceReferences(*_) >> {
            ServiceReference ref = Mock(ServiceReference)
            ref.getProperty(Constants.SERVICE_PID) >> createdPid
            return arguments[0].equals(ManagedService) ? [ref] : []
        }

        SystemMonitorWaitMock sysMonitor = new SystemMonitorWaitMock(bundleContext, configAdmin, featuresService, bundleService)

        when:
        returnedConfig = sysMonitor.createManagedFactoryService(createdPid, createdProps)

        then:
        1 * createdConfig.update(['propKey':'propValue'])
        returnedConfig.update(*_) >> {
            arguments[0].size() == createdProps.size()
            arguments[0].get('propKey') == createdProps.get('propKey')
        }

    }

    def 'failed while trying to create factory configuration'() {
        setup:
        def factoryPid = 'fpid'

        configAdmin.createFactoryConfiguration(*_) >> {
            throw new IOException()
        }
        SystemMonitorWaitMock sysMonitor = new SystemMonitorWaitMock(bundleContext, configAdmin, featuresService, bundleService)

        when:
        sysMonitor.createManagedFactoryService(factoryPid, null)

        then:
        thrown(SystemMonitorException)

    }

    def 'failed while trying to update created factory configuration'() {
        setup:
        def factoryPid = 'fpid'
        def createdPid = 'createdPid'
        def createdProps = ['propKey': 'propValue']
        def createdConfig = Mock(Configuration)
        createdConfig.getPid() >> createdPid

        configAdmin.createFactoryConfiguration(_, _) >> createdConfig


        createdConfig.update(_) >> {
            throw new IOException()
        }
        SystemMonitorWaitMock sysMonitor = new SystemMonitorWaitMock(bundleContext, configAdmin, featuresService, bundleService)


        when:
        sysMonitor.createManagedFactoryService(factoryPid, createdProps)

        then:
        thrown(SystemMonitorException)

    }

    def 'failed to retrieve service refs while waiting for managed service to appear'() {
        setup:
        def factoryPid = 'fpid'
        def createdProps = ['propKey': 'propValue']
        def createdConfig = Mock(Configuration)

        configAdmin.createFactoryConfiguration(_, _) >> createdConfig

        bundleContext.getServiceReferences(ManagedService.class, _) >> {
            throw new InvalidSyntaxException("msg", "filter")
        }
        SystemMonitorWaitMock sysMonitor = new SystemMonitorWaitMock(bundleContext, configAdmin, featuresService, bundleService)

        when:
        sysMonitor.createManagedFactoryService(factoryPid, createdProps)

        then:
        thrown(SystemMonitorException)
    }

    def 'failed to retrieve managed service factories refs while waiting for managed service to appear'() {
        setup:
        def factoryPid = 'fpid'
        def createdProps = ['propKey': 'propValue']
        def createdConfig = Mock(Configuration)

        configAdmin.createFactoryConfiguration(_, _) >> createdConfig

        bundleContext.getServiceReferences(ManagedServiceFactory.class, _) >> {
            throw new InvalidSyntaxException("msg", "filter")
        }
        SystemMonitorWaitMock sysMonitor = new SystemMonitorWaitMock(bundleContext, configAdmin, featuresService, bundleService)

        when:
        sysMonitor.createManagedFactoryService(factoryPid, createdProps)

        then:
        thrown(SystemMonitorException)
    }

    class SystemMonitorWaitMock extends SystemMonitorImpl {

        SystemMonitorWaitMock(BundleContext bundleContext, ConfigurationAdmin configAdmin, FeaturesService featuresService, BundleService bundleService) {
            super(bundleContext, configAdmin, featuresService, bundleService)
        }

        @Override
        protected boolean wait(Callable<Boolean> conditionIsMet, long maxWait, long pollInterval) throws Exception {
            return conditionIsMet.call()
        }
    }
}
