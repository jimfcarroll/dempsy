package net.dempsy;

import static net.dempsy.util.Functional.chain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Manager<T> {
    private static Logger LOGGER = LoggerFactory.getLogger(Manager.class);

    protected final Map<String, T> registered = new HashMap<>();
    protected final Class<T> clazz;

    public Manager(final Class<T> clazz) {
        this.clazz = clazz;
    }

    public T getAssociatedInstance(final String typeId) throws DempsyException {
        if(LOGGER.isTraceEnabled())
            LOGGER.trace("Trying to find " + clazz.getSimpleName() + " associated with the transport \"{}\"", typeId);

        T ret = null;

        synchronized(registered) {
            ret = registered.get(typeId);
            if(ret == null) {
                if(LOGGER.isTraceEnabled())
                    LOGGER.trace(clazz.getSimpleName() + " associated with the id \"{}\" wasn't already registered. Attempting to create one",
                        typeId);

                ret = makeInstance(typeId);
                registered.put(typeId, ret);
            }
        }

        return ret;
    }

    public T makeInstance(final String typeId) {
        final Set<Class<? extends T>> classes;

        // There's an issue opened on Reflections where multi-threaded access to the zip file is broken.
        // see: https://github.com/ronmamo/reflections/issues/81
        synchronized(Manager.class) {
            // So, this is a TOTAL HACK that makes sure the context classLoader is valid.
            // In at least one use of this library this call is made from a callback that's made
            // using JNA from a NATIVE "C" spawned thread which seems to have an invalid context
            // classloader (or at least one that has NO URLs that make up its classpath). This is
            // actually fixed by using a separate thread.
            final AtomicReference<Set<Class<? extends T>>> classesRef = new AtomicReference<>(null);
            chain(new Thread(() -> {
                // Assume it's a package name and the sender factory is in that package
                // classes = new Reflections(typeId + ".", new SubTypesScanner(false)).getSubTypesOf(clazz);
                try {
                    classesRef.set(new Reflections(new ConfigurationBuilder()
                        .setScanners(new SubTypesScanner())
                        .setUrls(ClasspathHelper.forJavaClassPath())
                        .addUrls(ClasspathHelper.forClassLoader(ClasspathHelper.contextClassLoader(), ClasspathHelper.staticClassLoader()))
                        .filterInputsBy(new FilterBuilder().include(FilterBuilder.prefix(typeId + "."))))
                            .getSubTypesOf(clazz));
                } catch(final Throwable rte) {
                    LOGGER.error("Failed to find classes from the package \"" + typeId + "\" that implement/extend " + clazz.getName(), rte);
                } finally {
                    if(classesRef.get() == null) // if there was any failure, we need to kick out the waiting thread.
                        classesRef.set(new HashSet<>());
                }
            }, "Dempsy-Manager-Temporary-Classloading-Thread"), t -> t.start());
            Set<Class<? extends T>> tmpClasses = null;
            while((tmpClasses = classesRef.get()) == null)
                Thread.yield();
            classes = tmpClasses;
        }

        T ret = null;
        if(classes != null && classes.size() > 0) {
            final Class<? extends T> sfClass = classes.iterator().next();
            if(classes.size() > 1)
                LOGGER.warn("Multiple " + clazz.getSimpleName() + " implementations in the package \"{}\". Going with {}", typeId,
                    sfClass.getName());

            try {
                ret = sfClass.newInstance();
            } catch(final InstantiationException | IllegalAccessException e) {
                throw new DempsyException(
                    "Failed to create an instance of the " + clazz.getSimpleName() + " \"" + sfClass.getName()
                        + "\". Is there a default constructor?",
                    e, false);
            }
        }

        if(ret == null)
            throw new DempsyException("Couldn't find a " + clazz.getSimpleName() + " registered with transport type id \"" + typeId
                + "\" and couldn't find an implementing class assuming the transport type id is a package name");
        return ret;
    }

    public void register(final String typeId, final T factory) {
        synchronized(registered) {
            final T oldFactory = registered.put(typeId, factory);

            if(oldFactory != null)
                LOGGER.info("Overridding an already registered " + clazz.getSimpleName() + "  for transport type id {}", typeId);
        }
    }
}
