/*
 * Copyright 2018 Red Hat, Inc, and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.unimbus;

import java.lang.annotation.Annotation;
import java.util.Set;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.AmbiguousResolutionException;
import javax.enterprise.inject.UnsatisfiedResolutionException;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.jboss.unimbus.classloading.ServiceRegistry;
import org.jboss.unimbus.classloading.ServiceRegistryClassLoader;
import org.jboss.unimbus.events.impl.EventEmitter;
import org.jboss.unimbus.ext.UNimbusProvidingExtension;
import org.jboss.unimbus.logging.impl.jdk.DefaultConsoleFormatter;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;

import static org.jboss.unimbus.Info.VERSION;
import static org.jboss.unimbus.impl.CoreMessages.MESSAGES;

/**
 * Root entry-point into the uNimbus system.
 *
 * <p>This class may be used staticly via {@link #run()}, as an executable jar's entry-point via {@link #main(String...)} or as an object.</p>
 *
 * @author Ken Finnigan
 * @author Bob McWhirter
 */
public class UNimbus {

    /**
     * Create an instance without arguments and start it.
     */
    public static void run() {
        UNimbus.run(null);
    }

    /**
     * Create an instance with the given configuration class and start it.
     *
     * @param configClass The configuration class.
     */
    public static void run(Class<?> configClass) {
        new UNimbus(configClass).start();
    }

    /**
     * Default executable entrypoint, which simply calls {@link #run()}.
     *
     * @param args Command-line arguments.
     */
    public static void main(String... args) {
        run();
    }

    /**
     * Construct a container without configuration.
     */
    public UNimbus() {
    }

    /**
     * Construct a container with the provided configuration.
     */
    public UNimbus(Class<?> configClass) {
        this.configClass = configClass;
        if (this.configClass != null) {
            this.classLoader = this.configClass.getClassLoader();
            this.serviceRegistryClassLoader.setDelegate(this.classLoader);
        }
    }

    /**
     * Construct a container with the provided classloader.
     *
     * @param classLoader The classloader.
     */
    public UNimbus(ClassLoader classLoader) {
        this.classLoader = classLoader;
        this.serviceRegistryClassLoader.setDelegate(classLoader);
    }

    /**
     * Retrieve a component by class and optional qualifier(s).
     *
     * @param cls        The type of the component to retrieve.
     * @param qualifiers Optional qualifiers to select the class.
     * @param <T>        The type of component to retrieve.
     * @return The component retrieved.
     * @throws AmbiguousResolutionException   if the ambiguous dependency resolution rules fail
     * @throws UnsatisfiedResolutionException if no beans are resolved
     */
    public <T> T get(Class<? extends T> cls, Annotation... qualifiers) throws AmbiguousResolutionException {
        Set<Bean<?>> beans = this.container.getBeanManager().getBeans(cls, qualifiers);
        if (beans.isEmpty()) {
            throw new UnsatisfiedResolutionException();
        }
        Bean<T> bean = (Bean<T>) this.container.getBeanManager().resolve(beans);
        CreationalContext<T> context = this.container.getBeanManager().createCreationalContext(bean);
        return bean.create(context);
    }


    /**
     * Start the container.
     *
     * <p>Will instantiate, inject and initialize all relevant CDI components.</p>
     *
     * <p>Additionally, {@link org.jboss.unimbus.events.LifecycleEvent}s will be fired throughout the phases.</p>
     *
     * <p>When this method returns successfully, the container will be full operational.</p>
     *
     * @return This container.
     */
    public UNimbus start() {

        bootstrapLogging();

        MESSAGES.versionInfo(VERSION);

        MESSAGES.starting();
        long startTick = System.currentTimeMillis();
        long curTick = startTick;

        /*
        Logger rootLogger = Logger.getLogger("");
        for (Handler handler : rootLogger.getHandlers()) {
            handler.setLevel(Level.FINEST);
        }
        */

        Logger noisy = Logger.getLogger("org.jboss.weld.Bootstrap");
        noisy.setLevel(Level.SEVERE);

        noisy = Logger.getLogger("org.jboss.weld.Version");
        noisy.setLevel(Level.SEVERE);

        Weld weld = new Weld();

        weld.addExtension(new UNimbusProvidingExtension(this));

        if (this.configClass != null) {
            weld.addPackages(true, this.configClass);
        }

        weld.setClassLoader(this.serviceRegistryClassLoader);
        Thread.currentThread().setContextClassLoader(this.classLoader);

        this.container = weld.initialize();
        curTick = markTiming("CDI initialize", curTick);

        EventEmitter emitter = this.container.select(EventEmitter.class).get();
        emitter.fireBootstrap();

        curTick = markTiming("bootstrap", curTick);
        emitter.fireScan();
        curTick = markTiming("scan", curTick);
        emitter.fireInitialize();
        curTick = markTiming("initialize", curTick);
        emitter.fireDeploy();
        curTick = markTiming("deploy", curTick);
        emitter.fireBeforeStart();
        curTick = markTiming("before start", curTick);
        emitter.fireStart();
        curTick = markTiming("start", curTick);
        emitter.fireAfterStart();
        curTick = markTiming("after start", curTick);

        long endTick = System.currentTimeMillis();
        MESSAGES.started(format(endTick - startTick));

        return this;
    }


    private long markTiming(String phase, long startTick) {
        long curTick = System.currentTimeMillis();
        MESSAGES.timing(phase, format(curTick - startTick));
        return curTick;
    }

    /**
     * Stop the container.
     */
    public void stop() {
        MESSAGES.stopping();
        ConfigProviderResolver.instance().releaseConfig(ConfigProvider.getConfig());
        this.container.shutdown();
        MESSAGES.stopped();
    }

    private static String format(long ms) {
        long seconds = ms / 1000;
        long milli = ms % 1000;
        return seconds + "." + milli + "s";
    }

    /**
     * Retrieve the underlying CDI {@link javax.enterprise.inject.spi.BeanManager}
     *
     * @return The underlying bean manager.
     */
    public BeanManager getBeanManager() {
        return this.container.getBeanManager();
    }

    private void bootstrapLogging() {
        Logger logger = Logger.getLogger("");
        logger.setLevel(Level.ALL);
        for (Handler handler : logger.getHandlers()) {
            handler.setLevel(Level.ALL);
            if (handler instanceof ConsoleHandler) {
                handler.setFormatter(new DefaultConsoleFormatter("%4$-6s: %1$tc [%3$s] %5$s%6$s%n"));
            }
        }
        logger.setLevel(Level.INFO);
    }

    public ClassLoader getClassLoader() {
        return this.classLoader;
    }

    public ServiceRegistry getServiceRegistry() {
        return this.serviceRegistryClassLoader;
    }

    public ClassLoader getApplicationClassLoader() {
        return this.serviceRegistryClassLoader;
    }

    private ClassLoader classLoader = UNimbus.class.getClassLoader();

    private ServiceRegistryClassLoader serviceRegistryClassLoader = new ServiceRegistryClassLoader(UNimbus.class.getClassLoader());

    private Class<?> configClass;

    private WeldContainer container;
}