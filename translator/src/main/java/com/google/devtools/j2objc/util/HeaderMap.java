/*
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

package com.google.devtools.j2objc.util;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.devtools.j2objc.ast.CompilationUnit;
import com.google.devtools.j2objc.jdt.BindingConverter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.lang.model.element.PackageElement;
import org.eclipse.jdt.core.dom.ITypeBinding;

/**
 * Manages the mapping of types to their header files.
 */
public class HeaderMap {

  /**
   * Public packages included by the j2objc libraries. This list is necessary so
   * that when package directories are suppressed, the platform headers can still
   * be found.
   */
  // TODO(tball): move this list to a distributed file, perhaps generated by build.
  private static final Set<String> PLATFORM_PACKAGES = Sets.newHashSet(new String[] {
      "android",
      "com.android.internal.util",
      "com.google.common",
      "com.google.common.annotations",
      "com.google.common.base",
      "com.google.common.cache",
      "com.google.common.collect",
      "com.google.common.hash",
      "com.google.common.io",
      "com.google.common.math",
      "com.google.common.net",
      "com.google.common.primitives",
      "com.google.common.util",
      "com.google.j2objc",
      "com.google.protobuf",
      "dalvik",
      "java",
      "javax",
      "junit",
      "libcore",
      "org.apache.harmony",
      "org.hamcrest",
      "org.json",
      "org.junit",
      "org.kxml2",
      "org.mockito",
      "org.w3c",
      "org.xml.sax",
      "org.xmlpull",
      "sun.misc",
  });

  private static final String DEFAULT_HEADER_MAPPING_FILE = "mappings.j2objc";

  /**
   * Types of output file generation. Output files are generated in
   * the specified output directory in an optional sub-directory.
   */
  public static enum OutputStyleOption {
    /** Use the class's package, like javac.*/
    PACKAGE,

    /** Use the relative directory of the input file. */
    SOURCE,

    /** Don't use a relative directory. */
    NONE
  }

  private OutputStyleOption outputStyle = OutputStyleOption.PACKAGE;

  // Variant of SOURCE style. Sources from .jar files are combined into a single output header and
  // source file.
  private boolean combineJars = false;
  // Variant of SOURCE style. Annotation generated sources are included in the same output as the
  // source they are generated from.
  private boolean includeGeneratedSources = false;

  private List<String> inputMappingFiles = null;
  private File outputMappingFile = null;
  private final Map<String, String> map = Maps.newHashMap();

  public void setOutputStyle(OutputStyleOption outputStyle) {
    this.outputStyle = outputStyle;
  }

  public void setCombineJars() {
    outputStyle = OutputStyleOption.SOURCE;
    combineJars = true;
  }

  public void setIncludeGeneratedSources() {
    outputStyle = OutputStyleOption.SOURCE;
    includeGeneratedSources = true;
  }

  public void setMappingFiles(String fileList) {
    if (fileList.isEmpty()) {
      // For when user supplies an empty mapping files list. Otherwise the default will be used.
      inputMappingFiles = Collections.emptyList();
    } else {
      inputMappingFiles = ImmutableList.copyOf(fileList.split(","));
    }
  }

  public void setOutputMappingFile(File outputMappingFile) {
    this.outputMappingFile = outputMappingFile;
  }

  /**
   * If true, generated source locations are determined as a function of the input source location
   * and not the package of the input source.
   */
  public boolean useSourceDirectories() {
    return outputStyle == OutputStyleOption.SOURCE;
  }

  public boolean combineSourceJars() {
    return outputStyle == OutputStyleOption.SOURCE && combineJars;
  }

  public boolean includeGeneratedSources() {
    return outputStyle == OutputStyleOption.SOURCE && includeGeneratedSources;
  }

  public String get(ITypeBinding type) {
    String explicitHeader = ElementUtil.getHeader(BindingConverter.getTypeElement(type));
    if (explicitHeader != null) {
      return explicitHeader;
    }

    String qualifiedName = type.getErasure().getQualifiedName();

    String mappedHeader = map.get(qualifiedName);
    if (mappedHeader != null) {
      return mappedHeader;
    }

    String name = type.getErasure().getName();
    PackageElement pkg = BindingConverter.getPackageElement(type.getPackage());
    return outputDirFromPackage(pkg) + name + ".h";
  }

  @VisibleForTesting
  public String getMapped(String qualifiedName) {
    return map.get(qualifiedName);
  }

  public String getOutputPath(CompilationUnit unit) {
    return outputDirFromPackage(unit.getPackage().getPackageElement()) + unit.getMainTypeName();
  }

  private String outputDirFromPackage(PackageElement pkg) {
    if (pkg == null || pkg.isUnnamed()) {
      return "";
    }
    String pkgName = ElementUtil.getName(pkg);
    OutputStyleOption style = outputStyle;
    if (isPlatformPackage(pkgName)) {
      // Use package directories for platform classes if they do not have an entry in the header
      // mapping.
      style = OutputStyleOption.PACKAGE;
    }
    switch (style) {
      case PACKAGE:
        return ElementUtil.getName(pkg).replace('.', File.separatorChar) + File.separatorChar;
      default:
        return "";
    }
  }

  public void put(String qualifiedName, String header) {
    map.put(qualifiedName, header);
  }

  private static boolean isPlatformPackage(String pkgName) {
    String[] parts = pkgName.split("\\.");
    String pkg = null;
    for (int i = 0; i < parts.length; i++) {
      pkg = i == 0 ? parts[0] : UnicodeUtils.format("%s.%s", pkg, parts[i]);
      if (PLATFORM_PACKAGES.contains(pkg)) {
        return true;
      }
    }
    return false;
  }

  public void loadMappings() {
    try {
      if (inputMappingFiles == null) {
        try {
          loadMappingsFromProperties(FileUtil.loadProperties(DEFAULT_HEADER_MAPPING_FILE));
        } catch (FileNotFoundException e) {
          // Don't fail if mappings aren't configured and the default mapping is absent.
        }
      } else {
        for (String resourceName : inputMappingFiles) {
          loadMappingsFromProperties(FileUtil.loadProperties(resourceName));
        }
      }
    } catch (IOException e) {
      ErrorUtil.error(e.getMessage());
    }
  }

  private void loadMappingsFromProperties(Properties mappings) {
    Enumeration<?> keyIterator = mappings.propertyNames();
    while (keyIterator.hasMoreElements()) {
      String key = (String) keyIterator.nextElement();
      map.put(key, mappings.getProperty(key));
    }
  }

  public void printMappings() {
    if (outputMappingFile == null) {
      return;
    }
    try {
      if (!outputMappingFile.exists()) {
        outputMappingFile.getParentFile().mkdirs();
        outputMappingFile.createNewFile();
      }
      PrintWriter writer = new PrintWriter(outputMappingFile, "UTF-8");

      for (Map.Entry<String, String> entry : map.entrySet()) {
        writer.println(UnicodeUtils.format("%s=%s", entry.getKey(), entry.getValue()));
      }

      writer.close();
    } catch (IOException e) {
      ErrorUtil.error(e.getMessage());
    }
  }
}
