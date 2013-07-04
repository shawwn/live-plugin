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
package liveplugin.pluginrunner;

import com.intellij.openapi.diagnostic.Logger;
import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.util.GroovyScriptEngine;
import org.codehaus.groovy.control.CompilationFailedException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import static liveplugin.MyFileUtil.asUrl;
import static liveplugin.MyFileUtil.findSingleFileIn;

public class GroovyPluginRunner implements PluginRunner {
	public static final String MAIN_SCRIPT = "plugin.groovy";
	private static final Logger LOG = Logger.getInstance(GroovyPluginRunner.class);

	private final ErrorReporter errorReporter;
	private final Map<String,String> environment;

	public GroovyPluginRunner(ErrorReporter errorReporter, Map<String, String> environment) {
		this.errorReporter = errorReporter;
		this.environment = new HashMap<String, String>(environment);
	}

	@Override public boolean canRunPlugin(String pathToPluginFolder) {
		return findSingleFileIn(pathToPluginFolder, MAIN_SCRIPT) != null;
	}

	@Override public void runPlugin(String pathToPluginFolder, String pluginId, Map<String, ?> binding) {
		File mainScript = findSingleFileIn(pathToPluginFolder, MAIN_SCRIPT);
		String pluginFolderUrl = "file:///" + pathToPluginFolder;
		runGroovyScript(asUrl(mainScript), pluginFolderUrl, pluginId, binding);
	}

	private void runGroovyScript(String mainScriptUrl, String pluginFolderUrl, String pluginId, Map<String, ?> binding) {
		try {
			environment.put("THIS_SCRIPT", mainScriptUrl);

			GroovyClassLoader classLoader = createClassLoaderWithDependencies(pluginFolderUrl, mainScriptUrl, pluginId);
			GroovyScriptEngine scriptEngine = new GroovyScriptEngine(pluginFolderUrl, classLoader);
			scriptEngine.run(mainScriptUrl, createGroovyBinding(binding));

		} catch (IOException e) {
			errorReporter.addLoadingError(pluginId, "Error while creating scripting engine. " + e.getMessage());
		} catch (CompilationFailedException e) {
			errorReporter.addLoadingError(pluginId, "Error while compiling script. " + e.getMessage());
		} catch (VerifyError e) {
			errorReporter.addLoadingError(pluginId, "Error while compiling script. " + e.getMessage());
		} catch (Exception e) {
			errorReporter.addRunningError(pluginId, e);
		}
	}

	private static Binding createGroovyBinding(Map<String, ?> binding) {
		Binding result = new Binding();
		for (Map.Entry<String, ?> entry : binding.entrySet()) {
			result.setVariable(entry.getKey(), entry.getValue());
		}
		return result;
	}

	private GroovyClassLoader createClassLoaderWithDependencies(String pluginFolderUrl, String mainScriptUrl, String pluginId) {
		GroovyClassLoader classLoader = new GroovyClassLoader(this.getClass().getClassLoader());

		try {
			URL url = new URL(pluginFolderUrl);
			classLoader.addURL(url);
			classLoader.addClasspath(url.getFile());

			BufferedReader inputStream = new BufferedReader(new InputStreamReader(new URL(mainScriptUrl).openStream()));
			String line;
			while ((line = inputStream.readLine()) != null) {
				if (line.startsWith(CLASSPATH_PREFIX)) {
					String path = line.replace(CLASSPATH_PREFIX, "").trim();

					path = inlineEnvironmentVariables(path, environment);
					if (!new File(path).exists()) {
						errorReporter.addLoadingError(pluginId, "Couldn't find dependency '" + path + "'");
					} else {
						classLoader.addURL(new URL("file:///" + path));
						classLoader.addClasspath(path);
					}
				}
			}
		} catch (IOException e) {
			errorReporter.addLoadingError(pluginId, "Error while looking for dependencies. Main script: " + mainScriptUrl + ". " + e.getMessage());
		}
		return classLoader;
	}

	private static String inlineEnvironmentVariables(String path, Map<String, String> environment) {
		boolean wasModified = false;
		for (Map.Entry<String, String> entry : environment.entrySet()) {
			path = path.replace("$" + entry.getKey(), entry.getValue());
			wasModified = true;
		}
		if (wasModified) LOG.info("Path with env variables: " + path);
		return path;
	}
}
