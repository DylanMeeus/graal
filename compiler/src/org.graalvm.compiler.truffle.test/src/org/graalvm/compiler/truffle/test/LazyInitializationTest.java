/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.truffle.test;

import static org.graalvm.compiler.test.SubprocessUtil.getVMCommandLine;
import static org.graalvm.compiler.test.SubprocessUtil.withoutDebuggerArguments;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.graalvm.compiler.core.CompilerThreadFactory;
import org.graalvm.compiler.core.common.util.Util;
import org.graalvm.compiler.debug.Assertions;
import org.graalvm.compiler.nodes.Cancellable;
import org.graalvm.compiler.options.OptionDescriptor;
import org.graalvm.compiler.options.OptionDescriptors;
import org.graalvm.compiler.options.OptionKey;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.options.OptionsParser;
import org.graalvm.compiler.test.SubprocessUtil;
import org.graalvm.compiler.test.SubprocessUtil.Subprocess;
import org.junit.Assert;
import org.junit.Test;

import jdk.vm.ci.runtime.JVMCICompilerFactory;
import jdk.vm.ci.services.JVMCIServiceLocator;

/**
 * Test lazy initialization of Graal in the context of Truffle. When simply executing Truffle code,
 * Graal should not be initialized unless there is an actual compilation request.
 */
public class LazyInitializationTest {

    private final Class<?> hotSpotVMEventListener;
    private final Class<?> hotSpotGraalCompilerFactoryOptions;
    private final Class<?> hotSpotGraalJVMCIServiceLocatorShared;
    private final Class<?> jvmciVersionCheck;

    private static boolean Java8OrEarlier = System.getProperty("java.specification.version").compareTo("1.9") < 0;

    public LazyInitializationTest() {
        hotSpotVMEventListener = forNameOrNull("jdk.vm.ci.hotspot.services.HotSpotVMEventListener");
        hotSpotGraalCompilerFactoryOptions = forNameOrNull("org.graalvm.compiler.hotspot.HotSpotGraalCompilerFactory$Options");
        hotSpotGraalJVMCIServiceLocatorShared = forNameOrNull("org.graalvm.compiler.hotspot.HotSpotGraalJVMCIServiceLocator$Shared");
        jvmciVersionCheck = forNameOrNull("org.graalvm.compiler.hotspot.JVMCIVersionCheck");
    }

    private static Class<?> forNameOrNull(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @Test
    public void testSLTck() throws IOException, InterruptedException {
        List<String> vmArgs = withoutDebuggerArguments(getVMCommandLine());
        vmArgs.add(Java8OrEarlier ? "-XX:+TraceClassLoading" : "-Xlog:class+init=info");
        vmArgs.add("-dsa");
        vmArgs.add("-da");
        vmArgs.add("-XX:-UseJVMCICompiler");
        Subprocess proc = SubprocessUtil.java(vmArgs, "com.oracle.mxtool.junit.MxJUnitWrapper", "com.oracle.truffle.sl.test.SLFactorialTest");
        int exitCode = proc.exitCode;
        if (exitCode != 0) {
            Assert.fail(String.format("non-zero exit code %d for command:%n%s", exitCode, proc));
        }

        ArrayList<String> loadedGraalClassNames = new ArrayList<>();
        int testCount = 0;
        for (String line : proc.output) {
            String loadedClass = extractClass(line);
            if (loadedClass != null) {
                if (isGraalClass(loadedClass)) {
                    loadedGraalClassNames.add(loadedClass);
                }
            } else if (line.startsWith("OK (")) {
                Assert.assertTrue(testCount == 0);
                int start = "OK (".length();
                int end = line.indexOf(' ', start);
                testCount = Integer.parseInt(line.substring(start, end));
            }
        }
        if (testCount == 0) {
            Assert.fail(String.format("no tests found in output of command:%n%s", testCount, proc));
        }

        try {
            checkAllowedGraalClasses(loadedGraalClassNames);
        } catch (AssertionError e) {
            throw new AssertionError(String.format("Failure for command:%n%s", proc), e);
        }
    }

    private static final Pattern CLASS_INIT_LOG_PATTERN = Pattern.compile("\\[info\\]\\[class,init\\] \\d+ Initializing '([^']+)'");

    /**
     * Extracts the class name from a line of log output.
     */
    private static String extractClass(String line) {
        if (Java8OrEarlier) {
            String traceClassLoadingPrefix = "[Loaded ";
            int index = line.indexOf(traceClassLoadingPrefix);
            if (index != -1) {
                int start = index + traceClassLoadingPrefix.length();
                int end = line.indexOf(' ', start);
                return line.substring(start, end);
            }
        } else {
            Matcher matcher = CLASS_INIT_LOG_PATTERN.matcher(line);
            if (matcher.find()) {
                return matcher.group(1).replace('/', '.');
            }
        }
        return null;
    }

    private static boolean isGraalClass(String className) {
        if (className.startsWith("org.graalvm.compiler.truffle.") || className.startsWith("org.graalvm.compiler.serviceprovider.")) {
            // Ignore classes in the com.oracle.graal.truffle package, they are all allowed.
            // Also ignore classes in the Graal service provider package, as they might not be
            // lazily loaded.
            return false;
        } else {
            return className.startsWith("org.graalvm.compiler.");
        }
    }

    private void checkAllowedGraalClasses(List<String> loadedGraalClassNames) {
        HashSet<Class<?>> whitelist = new HashSet<>();
        List<Class<?>> loadedGraalClasses = new ArrayList<>();

        for (String name : loadedGraalClassNames) {
            try {
                loadedGraalClasses.add(Class.forName(name));
            } catch (ClassNotFoundException e) {
                Assert.fail("loaded class " + name + " not found");
            }
        }
        /*
         * Look for all loaded OptionDescriptors classes, and whitelist the classes that declare the
         * options. They may be loaded by the option parsing code.
         */
        for (Class<?> cls : loadedGraalClasses) {
            if (OptionDescriptors.class.isAssignableFrom(cls)) {
                try {
                    OptionDescriptors optionDescriptors = cls.asSubclass(OptionDescriptors.class).newInstance();
                    for (OptionDescriptor option : optionDescriptors) {
                        whitelist.add(option.getDeclaringClass());
                        whitelist.add(option.getOptionValueType());
                        whitelist.add(option.getOptionType().getDeclaringClass());
                    }
                } catch (ReflectiveOperationException e) {
                }
            }
        }

        whitelist.add(Cancellable.class);

        List<String> forbiddenClasses = new ArrayList<>();
        for (Class<?> cls : loadedGraalClasses) {
            if (whitelist.contains(cls)) {
                continue;
            }

            if (!isGraalClassAllowed(cls)) {
                forbiddenClasses.add(cls.getName());
            }
        }
        if (!forbiddenClasses.isEmpty()) {
            Assert.fail("loaded forbidden classes:\n    " + forbiddenClasses.stream().collect(Collectors.joining("\n    ")) + "\n");
        }
    }

    private boolean isGraalClassAllowed(Class<?> cls) {
        if (CompilerThreadFactory.class.equals(cls)) {
            // The HotSpotTruffleRuntime creates a CompilerThreadFactory for Truffle.
            return true;
        }

        if (cls.equals(hotSpotGraalCompilerFactoryOptions)) {
            // Graal initialization needs to access this class.
            return true;
        }

        if (cls.equals(Util.class)) {
            // Provider of the Java runtime check utility used during Graal initialization.
            return true;
        }

        if (cls.equals(jvmciVersionCheck)) {
            // The Graal initialization needs to check the JVMCI version.
            return true;
        }

        if (JVMCICompilerFactory.class.isAssignableFrom(cls) || cls.getName().equals("org.graalvm.compiler.hotspot.IsGraalPredicate")) {
            // The compiler factories have to be loaded and instantiated by the JVMCI.
            return true;
        }

        if (JVMCIServiceLocator.class.isAssignableFrom(cls) || cls == hotSpotGraalJVMCIServiceLocatorShared) {
            return true;
        }

        if (OptionDescriptors.class.isAssignableFrom(cls) || OptionDescriptor.class.isAssignableFrom(cls)) {
            // If options are specified, the corresponding *_OptionDescriptors classes are loaded.
            return true;
        }

        if (cls == Assertions.class || cls == OptionsParser.class || cls == OptionValues.class || cls.getName().equals("org.graalvm.compiler.hotspot.HotSpotGraalOptionValues")) {
            // Classes implementing Graal option loading
            return true;
        }

        if (OptionKey.class.isAssignableFrom(cls)) {
            // If options are specified, that may implicitly load a custom OptionKey subclass.
            return true;
        }

        if (cls.getName().equals("org.graalvm.compiler.hotspot.HotSpotGraalMBean")) {
            // MBean is OK and fast
            return true;
        }

        if (hotSpotVMEventListener != null && hotSpotVMEventListener.isAssignableFrom(cls)) {
            // HotSpotVMEventListeners need to be loaded on JVMCI startup.
            return true;
        }

        // No other class from the org.graalvm.compiler package should be loaded.
        return false;
    }
}
