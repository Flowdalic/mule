
package org.mule.transport.nio.http;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mule.api.MuleException;
import org.mule.api.MuleRuntimeException;
import org.mule.api.config.ExceptionReader;
import org.mule.api.registry.ServiceType;
import org.mule.config.DefaultExceptionReader;
import org.mule.config.DefaultMuleConfiguration;
import org.mule.config.MuleExceptionReader;
import org.mule.config.NamingExceptionReader;
import org.mule.config.i18n.CoreMessages;
import org.mule.util.ClassUtils;
import org.mule.util.MapUtils;
import org.mule.util.SpiUtils;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * <code>ExceptionHelper</code> provides a number of helper functions that can be
 * useful for dealing with Mule exceptions. This class has 3 core functions -
 * <p/>
 * 1. ErrorCode lookup. A corresponding Mule error code can be found using for a
 * given Mule exception 2. Additional Error information such as Java doc url for a
 * given exception can be resolved using this class 3. Error code mappings can be
 * looked up by providing the the protocol to map to and the Mule exception.
 */

public final class ExceptionHelper
{
    /**
     * This is the property to set the error code to no the message it is the
     * property name the Transport provider uses set the set the error code on the
     * underlying message
     */
    public static final String ERROR_CODE_PROPERTY = "error.code.property";

    /**
     * logger used by this class
     */
    protected static final Log logger = LogFactory.getLog(ExceptionHelper.class);

    private static String J2SE_VERSION = "";

    /**
     * TODO How do you get the j2ee version??
     */
    private static final String J2EE_VERSION = "1.3ee";

    private static Properties errorDocs = new Properties();
    private static Properties errorCodes = new Properties();
    private static Map<String, Object> reverseErrorCodes = null;
    private static Map<String, Object> errorMappings = new HashMap<String, Object>();

    private static int exceptionThreshold = 0;
    private static boolean verbose = true;

    private static boolean initialised = false;

    /**
     * A list of the exception readers to use for different types of exceptions
     */
    private static List<ExceptionReader> exceptionReaders = new ArrayList<ExceptionReader>();

    /**
     * The default ExceptionReader which will be used for most types of exceptions
     */
    private static ExceptionReader defaultExceptionReader = new DefaultExceptionReader();

    static
    {
        initialise();
    }

    /**
     * Do not instantiate.
     */
    private ExceptionHelper()
    {
        // no-op
    }

    @SuppressWarnings("unchecked")
    private static void initialise()
    {
        try
        {
            if (initialised)
            {
                return;
            }

            registerExceptionReader(new MuleExceptionReader());
            registerExceptionReader(new NamingExceptionReader());
            J2SE_VERSION = System.getProperty("java.specification.version");

            String name = SpiUtils.SERVICE_ROOT + ServiceType.EXCEPTION.getPath()
                          + "/mule-exception-codes.properties";
            errorCodes = PropertiesLoaderUtils.loadAllProperties(name);

            reverseErrorCodes = MapUtils.invertMap(errorCodes);

            name = SpiUtils.SERVICE_ROOT + ServiceType.EXCEPTION.getPath()
                   + "/mule-exception-config.properties";
            errorDocs = PropertiesLoaderUtils.loadAllProperties(name);

            initialised = true;
        }
        catch (final Exception e)
        {
            throw new MuleRuntimeException(CoreMessages.failedToLoad("Exception resources"), e);
        }
    }

    public static int getErrorCode(final Class<?> exception)
    {
        final String code = errorCodes.getProperty(exception.getName(), "-1");
        return Integer.parseInt(code);
    }

    public static Class<?> getErrorClass(final int code)
    {
        final String key = String.valueOf(code);
        Object clazz = reverseErrorCodes.get(key);
        if (clazz == null)
        {
            return null;
        }
        else if (clazz instanceof Class)
        {
            return (Class<?>) clazz;
        }
        else
        {
            try
            {
                clazz = ClassUtils.loadClass(clazz.toString(), ExceptionHelper.class);
            }
            catch (final ClassNotFoundException e)
            {
                logger.error(e.getMessage(), e);
                return null;
            }
            reverseErrorCodes.put(key, clazz);
            return (Class<?>) clazz;
        }
    }

    private static Properties getErrorMappings(final String protocol, final ClassLoader classLoader)
    {
        final Object m = errorMappings.get(protocol);
        if (m != null)
        {
            if (m instanceof Properties)
            {
                return (Properties) m;
            }
            else
            {
                return null;
            }
        }
        else
        {
            final String name = SpiUtils.SERVICE_ROOT + ServiceType.EXCEPTION.getPath() + "/" + protocol
                                + "-exception-mappings.properties";
            Properties p = null;
            try
            {
                p = PropertiesLoaderUtils.loadAllProperties(name, classLoader);
            }
            catch (final IOException e)
            {
                throw new IllegalArgumentException("Failed to load resource: " + name);
            }

            errorMappings.put(protocol, p);
            return p;
        }
    }

    public static String getErrorMapping(String protocol,
                                         final Class<?> exception,
                                         final ClassLoader classLoader)
    {
        protocol = protocol.toLowerCase();
        final Properties mappings = getErrorMappings(protocol, classLoader);
        if (mappings == null)
        {
            logger.info("No mappings found for protocol: " + protocol);
            return String.valueOf(getErrorCode(exception));
        }

        Class<?> clazz = exception;
        String code = null;
        while (!clazz.equals(Object.class))
        {
            code = mappings.getProperty(clazz.getName());
            if (code == null)
            {
                clazz = clazz.getSuperclass();
            }
            else
            {
                return code;
            }
        }
        code = String.valueOf(getErrorCode(exception));
        // Finally lookup mapping based on error code and return the Mule error
        // code if a match is not found
        return mappings.getProperty(code, code);
    }

    public static String getJavaDocUrl(final Class<?> exception)
    {
        return getDocUrl("javadoc.", exception.getName());
    }

    public static String getDocUrl(final Class<?> exception)
    {
        return getDocUrl("doc.", exception.getName());
    }

    private static String getDocUrl(final String prefix, final String packageName)
    {
        String key = prefix;
        if (packageName.startsWith("java.") || packageName.startsWith("javax."))
        {
            key += J2SE_VERSION;
        }
        String url = getUrl(key, packageName);
        if (url == null && (packageName.startsWith("java.") || packageName.startsWith("javax.")))
        {
            key = prefix + J2EE_VERSION;
            url = getUrl(key, packageName);
        }
        if (url != null)
        {
            if (!url.endsWith("/"))
            {
                url += "/";
            }
            String s = packageName.replaceAll("[.]", "/");
            s += ".html";
            url += s;
        }
        return url;
    }

    private static String getUrl(String key, String packageName)
    {
        String url = null;
        if (!key.endsWith("."))
        {
            key += ".";
        }
        while (packageName.length() > 0)
        {
            url = errorDocs.getProperty(key + packageName, null);
            if (url == null)
            {
                final int i = packageName.lastIndexOf(".");
                if (i == -1)
                {
                    packageName = "";
                }
                else
                {
                    packageName = packageName.substring(0, i);
                }
            }
            else
            {
                break;
            }
        }
        return url;
    }

    public static Throwable getRootException(final Throwable t)
    {
        Throwable cause = t;
        Throwable root = null;
        while (cause != null)
        {
            root = cause;
            cause = getExceptionReader(cause).getCause(cause);
            // address some misbehaving exceptions, avoid endless loop
            if (t == cause)
            {
                break;
            }
        }

        return DefaultMuleConfiguration.fullStackTraces ? root : sanitize(root);
    }

    public static Throwable getNonMuleException(final Throwable t)
    {
        if (!(t instanceof MuleException))
        {
            return t;
        }
        Throwable cause = t;
        while (cause != null)
        {
            cause = getExceptionReader(cause).getCause(cause);
            // address some misbehaving exceptions, avoid endless loop
            if (t == cause || !(cause instanceof MuleException))
            {
                break;
            }
        }
        return cause instanceof MuleException ? null : cause;
    }

    public static Throwable sanitizeIfNeeded(final Throwable t)
    {
        return DefaultMuleConfiguration.fullStackTraces ? t : sanitize(t);
    }

    /**
     * Removes some internal Mule entries from the stacktrace. Modifies the passed-in
     * throwable stacktrace.
     */
    public static Throwable sanitize(final Throwable t)
    {
        if (t == null)
        {
            return null;
        }
        final StackTraceElement[] trace = t.getStackTrace();
        final List<StackTraceElement> newTrace = new ArrayList<StackTraceElement>();
        for (final StackTraceElement stackTraceElement : trace)
        {
            if (!isMuleInternalClass(stackTraceElement.getClassName()))
            {
                newTrace.add(stackTraceElement);
            }
        }

        final StackTraceElement[] clean = new StackTraceElement[newTrace.size()];
        newTrace.toArray(clean);
        t.setStackTrace(clean);

        Throwable cause = t.getCause();
        while (cause != null)
        {
            sanitize(cause);
            cause = cause.getCause();
        }

        return t;
    }

    /**
     * Removes some internal Mule entries from the stacktrace. Modifies the passed-in
     * throwable stacktrace.
     */
    public static Throwable summarise(Throwable t, final int depth)
    {
        t = sanitize(t);
        final StackTraceElement[] trace = t.getStackTrace();

        final int newStackDepth = Math.min(trace.length, depth);
        final StackTraceElement[] newTrace = new StackTraceElement[newStackDepth];

        System.arraycopy(trace, 0, newTrace, 0, newStackDepth);
        t.setStackTrace(newTrace);

        return t;
    }

    private static boolean isMuleInternalClass(final String className)
    {
        /*
         * Sacrifice the code quality for the sake of keeping things simple - the
         * alternative would be to pass MuleContext into every exception constructor.
         */
        for (final String mulePackage : DefaultMuleConfiguration.stackTraceFilter)
        {
            if (className.startsWith(mulePackage))
            {
                return true;
            }
        }
        return false;
    }

    public static Throwable getRootParentException(final Throwable t)
    {
        Throwable cause = t;
        Throwable parent = t;
        while (cause != null)
        {
            if (cause.getCause() == null)
            {
                return parent;
            }
            parent = cause;
            cause = getExceptionReader(cause).getCause(cause);
            // address some misbehaving exceptions, avoid endless loop
            if (t == cause)
            {
                break;
            }
        }
        return t;
    }

    public static MuleException getRootMuleException(final Throwable t)
    {
        Throwable cause = t;
        MuleException exception = null;
        while (cause != null)
        {
            if (cause instanceof MuleException)
            {
                exception = (MuleException) cause;
            }
            final Throwable tempCause = getExceptionReader(cause).getCause(cause);
            if (DefaultMuleConfiguration.fullStackTraces)
            {
                cause = tempCause;
            }
            else
            {
                cause = ExceptionHelper.sanitize(tempCause);
            }
            // address some misbehaving exceptions, avoid endless loop
            if (t == cause)
            {
                break;
            }
        }
        return exception;
    }

    public static List<Throwable> getExceptionsAsList(final Throwable t)
    {
        final List<Throwable> exceptions = new ArrayList<Throwable>();
        Throwable cause = t;
        while (cause != null)
        {
            exceptions.add(0, cause);
            cause = getExceptionReader(cause).getCause(cause);
            // address some misbehaving exceptions, avoid endless loop
            if (t == cause)
            {
                break;
            }
        }
        return exceptions;
    }

    public static Map<Object, Object> getExceptionInfo(final Throwable t)
    {
        final Map<Object, Object> info = new HashMap<Object, Object>();
        Throwable cause = t;
        while (cause != null)
        {
            info.putAll(getExceptionReader(cause).getInfo(cause));
            cause = getExceptionReader(cause).getCause(cause);
            // address some misbehaving exceptions, avoid endless loop
            if (t == cause)
            {
                break;
            }
        }
        return info;
    }

    public static String getExceptionStack(final Throwable t)
    {
        final StringBuilder buf = new StringBuilder();
        // get exception stack
        final List<Throwable> exceptions = getExceptionsAsList(t);

        int i = 1;
        for (final Iterator<Throwable> iterator = exceptions.iterator(); iterator.hasNext(); i++)
        {
            if (i > exceptionThreshold && exceptionThreshold > 0)
            {
                buf.append("(").append(exceptions.size() - i + 1).append(" more...)");
                break;
            }
            final Throwable throwable = iterator.next();
            final ExceptionReader er = getExceptionReader(throwable);
            buf.append(i).append(". ").append(er.getMessage(throwable)).append(" (");
            buf.append(throwable.getClass().getName()).append(")\n");
            if (verbose && throwable.getStackTrace().length > 0)
            {
                final StackTraceElement e = throwable.getStackTrace()[0];
                buf.append("  ")
                    .append(e.getClassName())
                    .append(":")
                    .append(e.getLineNumber())
                    .append(" (")
                    .append(getJavaDocUrl(throwable.getClass()))
                    .append(")\n");
            }
        }
        return buf.toString();
    }

    /**
     * Registers an exception reader with Mule
     * 
     * @param reader the reader to register.
     */
    public static void registerExceptionReader(final ExceptionReader reader)
    {
        exceptionReaders.add(reader);
    }

    public static <T> T traverseCauseHierarchy(Throwable e, final ExceptionEvaluator<T> evaluator)
    {
        final LinkedList<Throwable> exceptions = new LinkedList<Throwable>();
        exceptions.add(e);
        while (e.getCause() != null && !e.getCause().equals(e))
        {
            exceptions.addFirst(e.getCause());
            e = e.getCause();
        }
        for (final Throwable exception : exceptions)
        {
            final T value = evaluator.evaluate(exception);
            if (value != null)
            {
                return value;
            }
        }
        return null;
    }

    /**
     * Gets an exception reader for the exception
     * 
     * @param t the exception to get a reader for
     * @return either a specific reader or an instance of DefaultExceptionReader.
     *         This method never returns null;
     */
    public static ExceptionReader getExceptionReader(final Throwable t)
    {
        for (final ExceptionReader exceptionReader : exceptionReaders)
        {
            if (exceptionReader.getExceptionType().isInstance(t))
            {
                return exceptionReader;
            }
        }
        return defaultExceptionReader;
    }

    public static String writeException(final Throwable t)
    {
        final ExceptionReader er = getExceptionReader(t);
        final StringBuffer msg = new StringBuffer();
        msg.append(er.getMessage(t)).append(". Type: ").append(t.getClass());
        return msg.toString();
    }

    @SuppressWarnings("unchecked")
    public static <T extends Throwable> T unwrap(final T t)
    {
        if (t instanceof InvocationTargetException)
        {
            return (T) ((InvocationTargetException) t).getTargetException();
        }
        return t;

    }

    public static interface ExceptionEvaluator<T>
    {
        T evaluate(Throwable e);
    }
}