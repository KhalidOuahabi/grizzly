/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2008-2011 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 *
 * This file incorporates work covered by the following copyright and
 * permission notice:
 *
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glassfish.grizzly.http.server.util;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

// Depends: JDK1.1
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.grizzly.Grizzly;

/**
 * Utils for introspection and reflection
 */
public final class IntrospectionUtils {

    private static final Logger LOGGER = Grizzly.logger(IntrospectionUtils.class);

    /**
     * Call execute() - any ant-like task should work
     */
    public static void execute(Object proxy, String method) throws Exception {
        Method executeM = null;
        Class<?> c = proxy.getClass();
        Class<?> params[] = new Class[0];
        //	params[0]=args.getClass();
        executeM = findMethod(c, method, params);
        if (executeM == null) {
            throw new RuntimeException("No execute in " + proxy.getClass());
        }
        executeM.invoke(proxy, (Object[]) null);//new Object[] { args });
    }

    /**
     * Call void setAttribute( String ,Object )
     */
    public static void setAttribute(Object proxy, String n, Object v)
            throws Exception {
        if (proxy instanceof AttributeHolder) {
            ((AttributeHolder) proxy).setAttribute(n, v);
            return;
        }

        Method executeM = null;
        Class<?> c = proxy.getClass();
        Class<?> params[] = new Class[2];
        params[0] = String.class;
        params[1] = Object.class;
        executeM = findMethod(c, "setAttribute", params);
        if (executeM == null) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "No setAttribute in {0}", proxy.getClass());
            }
            return;
        }
        if (false) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Setting {0}={1}  in {2}", new Object[]{n, v, proxy});
            }
        }
        executeM.invoke(proxy, new Object[]{n, v});
        return;
    }

    /**
     * Call void getAttribute( String )
     */
    public static Object getAttribute(Object proxy, String n) throws Exception {
        Method executeM = null;
        Class<?> c = proxy.getClass();
        Class<?> params[] = new Class[1];
        params[0] = String.class;
        executeM = findMethod(c, "getAttribute", params);
        if (executeM == null) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "No getAttribute in {0}", proxy.getClass());
            }
            return null;
        }
        return executeM.invoke(proxy, new Object[]{n});
    }

    /**
     * Construct a URLClassLoader. Will compile and work in JDK1.1 too.
     */
    public static ClassLoader getURLClassLoader(URL urls[], ClassLoader parent) {
        try {
            Class<?> urlCL = Class.forName("java.net.URLClassLoader");
            Class<?> paramT[] = new Class[2];
            paramT[0] = urls.getClass();
            paramT[1] = ClassLoader.class;
            Method m = findMethod(urlCL, "newInstance", paramT);
            if (m == null) {
                return null;
            }

            ClassLoader cl = (ClassLoader) m.invoke(urlCL, new Object[]{urls,
                        parent});
            return cl;
        } catch (ClassNotFoundException ex) {
            // jdk1.1
            return null;
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "", ex);
            return null;
        }
    }

    public static String guessInstall(String installSysProp,
            String homeSysProp, String jarName) {
        return guessInstall(installSysProp, homeSysProp, jarName, null);
    }

    /**
     * Guess a product install/home by analyzing the class path. It works for
     * product using the pattern: lib/executable.jar or if executable.jar is
     * included in classpath by a shell script. ( java -jar also works )
     * 
     * Insures both "install" and "home" System properties are set. If either or
     * both System properties are unset, "install" and "home" will be set to the
     * same value. This value will be the other System property that is set, or
     * the guessed value if neither is set.
     */
    public static String guessInstall(String installSysProp,
            String homeSysProp, String jarName, String classFile) {
        String install = null;
        String home = null;

        if (installSysProp != null) {
            install = System.getProperty(installSysProp);
        }

        if (homeSysProp != null) {
            home = System.getProperty(homeSysProp);
        }

        if (install != null) {
            if (home == null) {
                System.getProperties().put(homeSysProp, install);
            }
            return install;
        }

        // Find the directory where jarName.jar is located

        String cpath = System.getProperty("java.class.path");
        String pathSep = System.getProperty("path.separator");
        StringTokenizer st = new StringTokenizer(cpath, pathSep);
        while (st.hasMoreTokens()) {
            String path = st.nextToken();
            //	    log( "path " + path );
            if (path.endsWith(jarName)) {
                home = path.substring(0, path.length() - jarName.length());
                try {
                    if ("".equals(home)) {
                        home = new File("./").getCanonicalPath();
                    } else if (home.endsWith(File.separator)) {
                        home = home.substring(0, home.length() - 1);
                    }
                    File f = new File(home);
                    String parentDir = f.getParent();
                    if (parentDir == null) {
                        parentDir = home; // unix style
                    }
                    File f1 = new File(parentDir);
                    install = f1.getCanonicalPath();
                    if (installSysProp != null) {
                        System.getProperties().put(installSysProp, install);
                    }
                    if (home == null && homeSysProp != null) {
                        System.getProperties().put(homeSysProp, install);
                    }
                    return install;
                } catch (Exception ex) {
                    LOGGER.log(Level.WARNING, "", ex);
                }
            } else {
                String fname = path + (path.endsWith("/") ? "" : "/")
                        + classFile;
                if (new File(fname).exists()) {
                    try {
                        File f = new File(path);
                        String parentDir = f.getParent();
                        if (parentDir == null) {
                            parentDir = path; // unix style
                        }
                        File f1 = new File(parentDir);
                        install = f1.getCanonicalPath();
                        if (installSysProp != null) {
                            System.getProperties().put(installSysProp, install);
                        }
                        if (home == null && homeSysProp != null) {
                            System.getProperties().put(homeSysProp, install);
                        }
                        return install;
                    } catch (Exception ex) {
                        LOGGER.log(Level.WARNING, "", ex);
                    }
                }
            }
        }

        // if install directory can't be found, use home as the default
        if (home != null) {
            System.getProperties().put(installSysProp, home);
            return home;
        }

        return null;
    }

    /**
     * Debug method, display the classpath
     */
    public static void displayClassPath(String msg, URL[] cp) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(msg);
            for (int i = 0; i < cp.length; i++) {
                LOGGER.fine(cp[i].getFile());
            }
        }
    }
    public final static String PATH_SEPARATOR = System.getProperty("path.separator");

    /**
     * Adds classpath entries from a vector of URL's to the "tc_path_add" System
     * property. This System property lists the classpath entries common to web
     * applications. This System property is currently used by Jasper when its
     * JSP servlet compiles the Java file for a JSP.
     */
    public static String classPathAdd(URL urls[], String cp) {
        if (urls == null) {
            return cp;
        }

        for (int i = 0; i < urls.length; i++) {
            if (cp != null) {
                cp += PATH_SEPARATOR + urls[i].getFile();
            } else {
                cp = urls[i].getFile();
            }
        }
        return cp;
    }

    /**
     * Find a method with the right name If found, call the method ( if param is
     * int or boolean we'll convert value to the right type before) - that means
     * you can have setDebug(1).
     */
    public static boolean setProperty(Object o, String name, String value) {
        if (dbg > 1) {
            d("setProperty(" + o.getClass() + " " + name + "=" + value + ")");
        }

        String setter = "set" + capitalize(name);

        try {
            Method methods[] = findMethods(o.getClass());
            Method setPropertyMethodVoid = null;
            Method setPropertyMethodBool = null;

            // First, the ideal case - a setFoo( String ) method
            for (int i = 0; i < methods.length; i++) {
                Class<?> paramT[] = methods[i].getParameterTypes();
                if (setter.equals(methods[i].getName()) && paramT.length == 1
                        && "java.lang.String".equals(paramT[0].getName())) {

                    methods[i].invoke(o, new Object[]{value});
                    return true;
                }
            }

            // Try a setFoo ( int ) or ( boolean )
            for (int i = 0; i < methods.length; i++) {
                boolean ok = true;
                if (setter.equals(methods[i].getName())
                        && methods[i].getParameterTypes().length == 1) {

                    // match - find the type and invoke it
                    Class<?> paramType = methods[i].getParameterTypes()[0];
                    Object params[] = new Object[1];

                    // Try a setFoo ( int )
                    if ("java.lang.Integer".equals(paramType.getName())
                            || "int".equals(paramType.getName())) {
                        try {
                            params[0] = new Integer(value);
                        } catch (NumberFormatException ex) {
                            ok = false;
                        }
                        // Try a setFoo ( long )
                    } else if ("java.lang.Long".equals(paramType.getName())
                            || "long".equals(paramType.getName())) {
                        try {
                            params[0] = new Long(value);
                        } catch (NumberFormatException ex) {
                            ok = false;
                        }

                        // Try a setFoo ( boolean )
                    } else if ("java.lang.Boolean".equals(paramType.getName())
                            || "boolean".equals(paramType.getName())) {
                        params[0] = Boolean.valueOf(value);

                        // Try a setFoo ( InetAddress )
                    } else if ("java.net.InetAddress".equals(paramType.getName())) {
                        try {
                            params[0] = InetAddress.getByName(value);
                        } catch (UnknownHostException exc) {
                            d("Unable to resolve host name:" + value);
                            ok = false;
                        }

                        // Unknown type
                    } else {
                        d("Unknown type " + paramType.getName());
                    }

                    if (ok) {
                        methods[i].invoke(o, params);
                        return true;
                    }
                }

                // save "setProperty" for later
                if ("setProperty".equals(methods[i].getName())) {
                    if (methods[i].getReturnType() == Boolean.TYPE) {
                        setPropertyMethodBool = methods[i];
                    } else {
                        setPropertyMethodVoid = methods[i];
                    }

                }
            }

            // Ok, no setXXX found, try a setProperty("name", "value")
            if (setPropertyMethodBool != null || setPropertyMethodVoid != null) {
                Object params[] = new Object[2];
                params[0] = name;
                params[1] = value;
                if (setPropertyMethodBool != null) {
                    try {
                        return (Boolean) setPropertyMethodBool.invoke(o, params);
                    } catch (IllegalArgumentException biae) {
                        //the boolean method had the wrong
                        //parameter types. lets try the other
                        if (setPropertyMethodVoid != null) {
                            setPropertyMethodVoid.invoke(o, params);
                            return true;
                        } else {
                            throw biae;
                        }
                    }
                } else {
                    setPropertyMethodVoid.invoke(o, params);
                    return true;
                }
            }

        } catch (IllegalArgumentException ex2) {
            LOGGER.log(Level.INFO, "IAE " + o + " " + name + " " + value, ex2);
        } catch (SecurityException ex1) {
            if (dbg > 0) {
                d("SecurityException for " + o.getClass() + " " + name + "="
                        + value + ")");
            }
            if (dbg > 1) {
                LOGGER.log(Level.WARNING, "", ex1);
            }
        } catch (IllegalAccessException iae) {
            if (dbg > 0) {
                d("IllegalAccessException for " + o.getClass() + " " + name
                        + "=" + value + ")");
            }
            if (dbg > 1) {
                LOGGER.log(Level.WARNING, "", iae);
            }
        } catch (InvocationTargetException ie) {
            if (dbg > 0) {
                d("InvocationTargetException for " + o.getClass() + " " + name
                        + "=" + value + ")");
            }
            if (dbg > 1) {
                LOGGER.log(Level.WARNING, "", ie);
            }
        }
        return false;
    }

    public static Object getProperty(Object o, String name) {
        String getter = "get" + capitalize(name);
        String isGetter = "is" + capitalize(name);

        try {
            Method methods[] = findMethods(o.getClass());
            Method getPropertyMethod = null;

            // First, the ideal case - a getFoo() method
            for (int i = 0; i < methods.length; i++) {
                Class<?> paramT[] = methods[i].getParameterTypes();
                if (getter.equals(methods[i].getName()) && paramT.length == 0) {
                    return methods[i].invoke(o, (Object[]) null);
                }
                if (isGetter.equals(methods[i].getName()) && paramT.length == 0) {
                    return methods[i].invoke(o, (Object[]) null);
                }

                if ("getProperty".equals(methods[i].getName())) {
                    getPropertyMethod = methods[i];
                }
            }

            // Ok, no setXXX found, try a getProperty("name")
            if (getPropertyMethod != null) {
                Object params[] = new Object[1];
                params[0] = name;
                return getPropertyMethod.invoke(o, params);
            }

        } catch (IllegalArgumentException ex2) {
            LOGGER.log(Level.INFO, "IAE " + o + " " + name, ex2);
        } catch (SecurityException ex1) {
            if (dbg > 0) {
                d("SecurityException for " + o.getClass() + " " + name + ")");
            }
            if (dbg > 1) {
                LOGGER.log(Level.WARNING, "", ex1);
            }
        } catch (IllegalAccessException iae) {
            if (dbg > 0) {
                d("IllegalAccessException for " + o.getClass() + " " + name
                        + ")");
            }
            if (dbg > 1) {
                LOGGER.log(Level.WARNING, "", iae);
            }
        } catch (InvocationTargetException ie) {
            if (dbg > 0) {
                d("InvocationTargetException for " + o.getClass() + " " + name
                        + ")");
            }
            if (dbg > 1) {
                LOGGER.log(Level.WARNING, "", ie);
            }
        }
        return null;
    }

    /** 
     */
    public static void setProperty(Object o, String name) {
        String setter = "set" + capitalize(name);
        try {
            Method methods[] = findMethods(o.getClass());
            Method setPropertyMethod = null;
            // find setFoo() method
            for (int i = 0; i < methods.length; i++) {
                Class<?> paramT[] = methods[i].getParameterTypes();
                if (setter.equals(methods[i].getName()) && paramT.length == 0) {
                    methods[i].invoke(o, new Object[]{});
                    return;
                }
            }
        } catch (Exception ex1) {
            if (dbg > 0) {
                d("Exception for " + o.getClass() + " " + name);
            }
            if (dbg > 1) {
                LOGGER.log(Level.WARNING, "", ex1);
            }
        }
    }

    /**
     * Replace ${NAME} with the property value
     */
    public static String replaceProperties(String value,
            Map<String, String> staticProp, PropertySource dynamicProp[]) {
        if (value.indexOf("$") < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder();
        int prev = 0;
        // assert value!=nil
        int pos;
        while ((pos = value.indexOf("$", prev)) >= 0) {
            if (pos > 0) {
                sb.append(value.substring(prev, pos));
            }
            if (pos == (value.length() - 1)) {
                sb.append('$');
                prev = pos + 1;
            } else if (value.charAt(pos + 1) != '{') {
                sb.append('$');
                prev = pos + 1; // XXX
            } else {
                int endName = value.indexOf('}', pos);
                if (endName < 0) {
                    sb.append(value.substring(pos));
                    prev = value.length();
                    continue;
                }
                String n = value.substring(pos + 2, endName);
                String v = null;
                if (staticProp != null) {
                    v = staticProp.get(n);
                }
                if (v == null && dynamicProp != null) {
                    for (int i = 0; i < dynamicProp.length; i++) {
                        v = dynamicProp[i].getProperty(n);
                        if (v != null) {
                            break;
                        }
                    }
                }
                if (v == null) {
                    v = "${" + n + "}";
                }

                sb.append(v);
                prev = endName + 1;
            }
        }
        if (prev < value.length()) {
            sb.append(value.substring(prev));
        }
        return sb.toString();
    }

    /**
     * Reverse of Introspector.decapitalize
     */
    public static String capitalize(String name) {
        if (name == null || name.length() == 0) {
            return name;
        }
        char chars[] = name.toCharArray();
        chars[0] = Character.toUpperCase(chars[0]);
        return new String(chars);
    }

    public static String unCapitalize(String name) {
        if (name == null || name.length() == 0) {
            return name;
        }
        char chars[] = name.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }

    // -------------------- Class path tools --------------------
    /**
     * Add all the jar files in a dir to the classpath, represented as a Vector
     * of URLs.
     */
    @SuppressWarnings("unchecked")
    public static void addToClassPath(List<URL> cpV, String dir) {
        try {
            String cpComp[] = getFilesByExt(dir, ".jar");
            if (cpComp != null) {
                int jarCount = cpComp.length;
                for (int i = 0; i < jarCount; i++) {
                    URL url = getURL(dir, cpComp[i]);
                    if (url != null) {
                        cpV.add(url);
                    }
                }
            }
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "", ex);
        }
    }

    @SuppressWarnings("unchecked")
    public static void addToolsJar(List<URL> v) {
        try {
            // Add tools.jar in any case
            File f = new File(System.getProperty("java.home")
                    + "/../lib/tools.jar");

            if (!f.exists()) {
                // On some systems java.home gets set to the root of jdk.
                // That's a bug, but we can work around and be nice.
                f = new File(System.getProperty("java.home") + "/lib/tools.jar");
                if (f.exists()) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.log(Level.FINE,
                                "Detected strange java.home value {0}, it should point to jre",
                                System.getProperty("java.home"));
                    }
                }
            }
            URL url = new URL("file", "", f.getAbsolutePath());

            v.add(url);
        } catch (MalformedURLException ex) {
            LOGGER.log(Level.WARNING, "", ex);
        }
    }

    /**
     * Return all files with a given extension in a dir
     */
    public static String[] getFilesByExt(String ld, String ext) {
        File dir = new File(ld);
        String[] names = null;
        final String lext = ext;
        if (dir.isDirectory()) {
            names = dir.list(new FilenameFilter() {

                @Override
                public boolean accept(File d, String name) {
                    if (name.endsWith(lext)) {
                        return true;
                    }
                    return false;
                }
            });
        }
        return names;
    }

    /**
     * Construct a file url from a file, using a base dir
     */
    public static URL getURL(String base, String file) {
        try {
            File baseF = new File(base);
            File f = new File(baseF, file);
            String path = f.getCanonicalPath();
            if (f.isDirectory()) {
                path += "/";
            }
            if (!f.exists()) {
                return null;
            }
            return new URL("file", "", path);
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "", ex);
            return null;
        }
    }

    /**
     * Add elements from the classpath <i>cp </i> to a Vector <i>jars </i> as
     * file URLs (We use Vector for JDK 1.1 compat).
     * <p>
     * 
     * @param jars The jar list
     * @param cp a String classpath of directory or jar file elements
     *   separated by path.separator delimiters.
     * @throws IOException If an I/O error occurs
     * @throws MalformedURLException Doh ;)
     */
    @SuppressWarnings("unchecked")
    public static void addJarsFromClassPath(List<URL> jars, String cp)
            throws IOException, MalformedURLException {
        String sep = System.getProperty("path.separator");
        String token;
        StringTokenizer st;
        if (cp != null) {
            st = new StringTokenizer(cp, sep);
            while (st.hasMoreTokens()) {
                File f = new File(st.nextToken());
                String path = f.getCanonicalPath();
                if (f.isDirectory()) {
                    path += "/";
                }
                URL url = new URL("file", "", path);
                if (!jars.contains(url)) {
                    jars.add(url);
                }
            }
        }
    }

    /**
     * Return a URL[] that can be used to construct a class loader
     */
    public static URL[] getClassPath(List<URL> v) {
        URL[] urls = new URL[v.size()];
        for (int i = 0; i < v.size(); i++) {
            urls[i] = v.get(i);
        }
        return urls;
    }

    /**
     * Construct a URL classpath from files in a directory, a cpath property,
     * and tools.jar.
     */
    @SuppressWarnings("unchecked")
    public static URL[] getClassPath(String dir, String cpath,
            String cpathProp, boolean addTools) throws IOException,
            MalformedURLException {
        List<URL> jarsV = new ArrayList<URL>();
        if (dir != null) {
            // Add dir/classes first, if it exists
            URL url = getURL(dir, "classes");
            if (url != null) {
                jarsV.add(url);
            }
            addToClassPath(jarsV, dir);
        }

        if (cpath != null) {
            addJarsFromClassPath(jarsV, cpath);
        }

        if (cpathProp != null) {
            String cpath1 = System.getProperty(cpathProp);
            addJarsFromClassPath(jarsV, cpath1);
        }

        if (addTools) {
            addToolsJar(jarsV);
        }

        return getClassPath(jarsV);
    }

    // -------------------- Mapping command line params to setters
    @SuppressWarnings({"unchecked"})
    public static boolean processArgs(Object proxy, String args[])
            throws Exception {
        String args0[] = null;
        if (null != findMethod(proxy.getClass(), "getOptions1", new Class[]{})) {
            args0 = (String[]) callMethod0(proxy, "getOptions1");
        }

        if (args0 == null) {
            //args0=findVoidSetters(proxy.getClass());
            args0 = findBooleanSetters(proxy.getClass());
        }
        Map<String, String> h = null;
        if (null != findMethod(proxy.getClass(), "getOptionAliases",
                new Class[]{})) {
            h = (Map<String, String>) callMethod0(proxy, "getOptionAliases");
        }
        return processArgs(proxy, args, args0, null, h);
    }

    public static boolean processArgs(Object proxy, String args[],
            String args0[], String args1[],
            Map<String, String> aliases) throws Exception {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("-")) {
                arg = arg.substring(1);
            }
            if (aliases != null && aliases.get(arg) != null) {
                arg = aliases.get(arg);
            }

            if (args0 != null) {
                boolean set = false;
                for (int j = 0; j < args0.length; j++) {
                    if (args0[j].equalsIgnoreCase(arg)) {
                        setProperty(proxy, args0[j], "true");
                        set = true;
                        break;
                    }
                }
                if (set) {
                    continue;
                }
            }
            if (args1 != null) {
                for (int j = 0; j < args1.length; j++) {
                    if (args1[j].equalsIgnoreCase(arg)) {
                        i++;
                        if (i >= args.length) {
                            return false;
                        }
                        setProperty(proxy, arg, args[i]);
                        break;
                    }
                }
            } else {
                // if args1 is not specified,assume all other options have param
                i++;
                if (i >= args.length) {
                    return false;
                }
                setProperty(proxy, arg, args[i]);
            }

        }
        return true;
    }

    // -------------------- other utils --------------------
    public static void clear() {
        objectMethods.clear();
    }

    @SuppressWarnings("unchecked")
    public static String[] findVoidSetters(Class<?> c) {
        Method m[] = findMethods(c);
        if (m == null) {
            return null;
        }
        List<String> v = new ArrayList<String>();
        for (int i = 0; i < m.length; i++) {
            if (m[i].getName().startsWith("set")
                    && m[i].getParameterTypes().length == 0) {
                String arg = m[i].getName().substring(3);
                v.add(unCapitalize(arg));
            }
        }
        String s[] = new String[v.size()];
        for (int i = 0; i < s.length; i++) {
            s[i] = (String) v.get(i);
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    public static String[] findBooleanSetters(Class<?> c) {
        Method m[] = findMethods(c);
        if (m == null) {
            return null;
        }
        List<String> v = new ArrayList<String>();
        for (int i = 0; i < m.length; i++) {
            if (m[i].getName().startsWith("set")
                    && m[i].getParameterTypes().length == 1
                    && "boolean".equalsIgnoreCase(m[i].getParameterTypes()[0].getName())) {
                String arg = m[i].getName().substring(3);
                v.add(unCapitalize(arg));
            }
        }
        String s[] = new String[v.size()];
        for (int i = 0; i < s.length; i++) {
            s[i] = v.get(i);
        }
        return s;
    }
    static Map<Class<?>, Method[]> objectMethods =
            new HashMap<Class<?>, Method[]>();

    @SuppressWarnings("unchecked")
    public static Method[] findMethods(Class<?> c) {
        Method methods[] = (Method[]) objectMethods.get(c);
        if (methods != null) {
            return methods;
        }

        methods = c.getMethods();
        objectMethods.put(c, methods);
        return methods;
    }

    public static Method findMethod(Class<?> c, String name,
            Class<?> params[]) {
        Method methods[] = findMethods(c);
        if (methods == null) {
            return null;
        }
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].getName().equals(name)) {
                Class<?> methodParams[] = methods[i].getParameterTypes();
                if (methodParams == null) {
                    if (params == null || params.length == 0) {
                        return methods[i];
                    }
                }
                if (params == null) {
                    if (methodParams.length == 0) {
                        return methods[i];
                    }
                }
                if (params != null && methodParams != null) {
                    if (params.length != methodParams.length) {
                        continue;
                    }
                }
                boolean found = true;
                if (params != null) {
                    for (int j = 0; j < params.length; j++) {
                        if (methodParams != null) {
                            if (params[j] != methodParams[j]) {
                                found = false;
                                break;
                            }
                        }
                    }
                }
                if (found) {
                    return methods[i];
                }
            }
        }
        return null;
    }

    /** Test if the object implements a particular
     *  method
     */
    public static boolean hasHook(Object obj, String methodN) {
        try {
            Method myMethods[] = findMethods(obj.getClass());
            for (int i = 0; i < myMethods.length; i++) {
                if (methodN.equals(myMethods[i].getName())) {
                    // check if it's overriden
                    Class<?> declaring = myMethods[i].getDeclaringClass();
                    Class<?> parentOfDeclaring = declaring.getSuperclass();
                    // this works only if the base class doesn't extend
                    // another class.

                    // if the method is declared in a top level class
                    // like BaseInterceptor parent is Object, otherwise
                    // parent is BaseInterceptor or an intermediate class
                    if (!"java.lang.Object".equals(parentOfDeclaring.getName())) {
                        return true;
                    }
                }
            }
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "", ex);
        }
        return false;
    }

    public static void callMain(Class<?> c, String args[]) throws Exception {
        Class<?> p[] = new Class[1];
        p[0] = args.getClass();
        @SuppressWarnings("unchecked")
        Method m = c.getMethod("main", p);
        m.invoke(c, new Object[]{args});
    }

    public static Object callMethod1(Object target, String methodN,
            Object param1, String typeParam1, ClassLoader cl) throws Exception {
        if (target == null || param1 == null) {
            d("Assert: Illegal params " + target + " " + param1);
            return null;
        }
        if (dbg > 0) {
            d("callMethod1 " + target.getClass().getName() + " "
                    + param1.getClass().getName() + " " + typeParam1);
        }

        Class<?> params[] = new Class[1];
        if (typeParam1 == null) {
            params[0] = param1.getClass();
        } else {
            params[0] = cl.loadClass(typeParam1);
        }
        Method m = findMethod(target.getClass(), methodN, params);
        if (m == null) {
            throw new NoSuchMethodException(target.getClass().getName() + " "
                    + methodN);
        }
        return m.invoke(target, new Object[]{param1});
    }

    public static Object callMethod0(Object target, String methodN)
            throws Exception {
        if (target == null) {
            d("Assert: Illegal params: target is null");
            return null;
        }
        if (dbg > 0) {
            d("callMethod0 " + target.getClass().getName() + "." + methodN);
        }

        Class params[] = new Class[0];
        Method m = findMethod(target.getClass(), methodN, params);
        if (m == null) {
            throw new NoSuchMethodException(target.getClass().getName() + " "
                    + methodN);
        }
        return m.invoke(target, emptyArray);
    }
    static Object[] emptyArray = new Object[]{};

    public static Object callMethodN(Object target, String methodN,
            Object params[], Class<?> typeParams[]) throws Exception {
        Method m = null;
        m = findMethod(target.getClass(), methodN, typeParams);
        if (m == null) {
            d("Can't find method " + methodN + " in " + target + " CLASS "
                    + target.getClass());
            return null;
        }
        Object o = m.invoke(target, params);

        if (dbg > 0) {
            // debug
            StringBuilder sb = new StringBuilder();
            sb.append("").append(target.getClass().getName()).append(".").append(methodN).append("( ");
            for (int i = 0; i < params.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(params[i]);
            }
            sb.append(")");
            d(sb.toString());
        }
        return o;
    }

    public static Object convert(String object, Class<?> paramType) {
        Object result = null;
        if ("java.lang.String".equals(paramType.getName())) {
            result = object;
        } else if ("java.lang.Integer".equals(paramType.getName())
                || "int".equals(paramType.getName())) {
            try {
                result = new Integer(object);
            } catch (NumberFormatException ex) {
            }
            // Try a setFoo ( boolean )
        } else if ("java.lang.Boolean".equals(paramType.getName())
                || "boolean".equals(paramType.getName())) {
            result = Boolean.valueOf(object);

            // Try a setFoo ( InetAddress )
        } else if ("java.net.InetAddress".equals(paramType.getName())) {
            try {
                result = InetAddress.getByName(object);
            } catch (UnknownHostException exc) {
                d("Unable to resolve host name:" + object);
            }

            // Unknown type
        } else {
            d("Unknown type " + paramType.getName());
        }
        if (result == null) {
            throw new IllegalArgumentException("Can't convert argument: " + object);
        }
        return result;
    }

    // -------------------- Get property --------------------
    // This provides a layer of abstraction
    public static interface PropertySource {

        public String getProperty(String key);
    }

    public static interface AttributeHolder {

        public void setAttribute(String key, Object o);
    }
    // debug --------------------
    static final int dbg = 0;

    static void d(String s) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "IntrospectionUtils: {0}", s);
        }
    }
}