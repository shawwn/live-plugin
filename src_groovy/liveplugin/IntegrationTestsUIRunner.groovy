package liveplugin

import com.intellij.execution.ExecutionManager
import com.intellij.execution.Location
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationPerRunnerSettings
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.execution.junit2.TestProxy
import com.intellij.execution.junit2.info.TestInfo
import com.intellij.execution.junit2.segments.ObjectReader
import com.intellij.execution.junit2.states.Statistics
import com.intellij.execution.junit2.states.TestState
import com.intellij.execution.junit2.ui.ConsolePanel
import com.intellij.execution.junit2.ui.actions.JUnitToolbarPanel
import com.intellij.execution.junit2.ui.model.CompletionEvent
import com.intellij.execution.junit2.ui.model.JUnitRunningModel
import com.intellij.execution.junit2.ui.model.TreeCollapser
import com.intellij.execution.junit2.ui.properties.JUnitConsoleProperties
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.BasicProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.Printer
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.ToolbarPanel
import com.intellij.execution.testframework.ui.BaseTestsOutputConsoleView
import com.intellij.execution.testframework.ui.TestResultsPanel
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.actions.CloseAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import org.junit.Ignore
import org.junit.Test

import javax.swing.*
import java.lang.reflect.Method

import static com.intellij.execution.ui.ConsoleViewContentType.ERROR_OUTPUT
import static com.intellij.execution.ui.ConsoleViewContentType.NORMAL_OUTPUT
import static com.intellij.rt.execution.junit.states.PoolOfTestStates.*
import static liveplugin.IntegrationTestsTextRunner.createInstanceOf

class IntegrationTestsUIRunner {

	static def runIntegrationTests(List<Class> testClasses, @NotNull Project project, @Nullable String pluginPath = null) {
		def context = [project: project, pluginPath: pluginPath]

		def jUnitPanel = new JUnitPanel().showIn(project)
		jUnitPanel.startedAllTests()
		testClasses.collect{ runTestsInClass(it, context, jUnitPanel) }
		jUnitPanel.finishedAllTests()
	}

	private static runTestsInClass(Class testClass, Map context, JUnitPanel jUnitPanel) {
		def isTest = { Method method -> method.annotations.find{ it instanceof Test} }
		def isIgnored = { Method method -> method.annotations.find{ it instanceof Ignore} }

		testClass.declaredMethods.findAll{ isTest(it) }.each{ method ->
			if (isIgnored(method))
				ignoreTest(testClass.name, method.name, jUnitPanel)
			else
				runTest(testClass.name, method.name, jUnitPanel) { method.invoke(createInstanceOf(testClass, context)) }
		}

		jUnitPanel.finishedClass(testClass.name)
	}

	private static ignoreTest(String className, String methodName, JUnitPanel jUnitPanel) {
		jUnitPanel.running(className, methodName)
		jUnitPanel.ignored(methodName)
	}

	private static runTest(String className, String methodName, JUnitPanel jUnitPanel, Closure closure) {
		try {
			jUnitPanel.running(className, methodName)
			closure()
			jUnitPanel.passed(methodName)
		} catch (Exception e) {
			jUnitPanel.error(methodName, asString(e))
		} catch (AssertionError e) {
			jUnitPanel.failed(methodName, asString(e))
		}
	}

	private static asString(Throwable throwable) {
		def writer = new StringWriter()
		throwable.printStackTrace(new PrintWriter(writer))
		writer.buffer.toString()
	}


	static class JUnitPanel {
		@Delegate private TestProxyUpdater testProxyUpdater
		private ProcessHandler handler
		private JUnitRunningModel model
		private TestProxy rootTestProxy
		private long allTestsStartTime

		def showIn(Project project) {
			def executor = DefaultRunExecutor.getRunExecutorInstance()

			JUnitConfiguration myConfiguration = new JUnitConfiguration("Temp config", project, new JUnitConfigurationType().configurationFactories.first())
			JUnitConsoleProperties consoleProperties = new JUnitConsoleProperties(myConfiguration, executor)

			ConfigurationFactory factory = new JUnitConfigurationType().configurationFactories.first()
			RunnerAndConfigurationSettings runnerAndConfigSettings = RunManager.getInstance(project).createRunConfiguration("Temp config", factory)
			ExecutionEnvironment myEnvironment = new ExecutionEnvironment(new BasicProgramRunner(), runnerAndConfigSettings, (Project) project)

			handler = new ProcessHandler() {
				@Override protected void destroyProcessImpl() { notifyProcessTerminated(0) }
				@Override protected void detachProcessImpl() { notifyProcessDetached() }
				@Override boolean detachIsDefault() { true }
				@Override OutputStream getProcessInput() { new ByteArrayOutputStream() }
			}

			rootTestProxy = new TestProxy(newTestInfo("Integration tests"))
			model = new JUnitRunningModel(rootTestProxy, consoleProperties)

			def runnerSettings = (RunnerSettings) myEnvironment.getRunnerSettings()
			def configurationSettings = (ConfigurationPerRunnerSettings) myEnvironment.configurationSettings

			def additionalActions = new AppendAdditionalActions(project, "Integration tests")
			def consoleView = new MyTreeConsoleView(consoleProperties, myEnvironment, rootTestProxy, additionalActions)
			consoleView.initUI()
			consoleView.attachToProcess(handler)
			consoleView.attachToModel(model)

			//Disposer.register(consoleView, rootTestProxy)

			ExecutionManager.getInstance(project).contentManager.showRunContent(executor, additionalActions.descriptor)

			testProxyUpdater = new TestProxyUpdater(rootTestProxy)
			this
		}

		def startedAllTests(long now = System.currentTimeMillis()) {
			handler.startNotify()
			allTestsStartTime = now
		}

		def finishedAllTests(long now = System.currentTimeMillis()) {
			handler.destroyProcess()

			testProxyUpdater.finished()

			model.notifier.onFinished()
			model.notifier.fireRunnerStateChanged(new CompletionEvent(true, now - allTestsStartTime))
		}

		static TestInfo newTestInfo(String name) {
			new TestInfo() {
				@Override String getName() { name }
				@Override String getComment() { "" }
				@Override void readFrom(ObjectReader objectReader) {}
				@Override Location getLocation(Project project) { null }
			}
		}
	}

	private static class TestProxyUpdater {
		private static final runningState = newTestState(RUNNING_INDEX, null, false)
		private static final passedState = newTestState(PASSED_INDEX)
		private static final failedState = newTestState(FAILED_INDEX)
		private static final errorState = newTestState(ERROR_INDEX)
		private static final ignoredState = newTestState(IGNORED_INDEX)

		private final def testProxyByClassName = new HashMap<String, TestProxy>().withDefault{ String className ->
			int i = className.lastIndexOf(".")
			if (i == -1 || i == className.size() - 1) {
				new TestProxy(newTestInfo(className))
			} else {
				def simpleClassName = className[i + 1..-1]
				def classPackage = className[0..i - 1]
				new TestProxy(newTestInfo(simpleClassName, classPackage))
			}
		}
		private final def testProxyByMethodName = new HashMap<String, TestProxy>().withDefault{ methodName ->
			new TestProxy(newTestInfo(methodName))
		}
		private final def testStartTimeByMethodName = new HashMap<String, Long>()
		private final def testStartTimeByClassName = new HashMap<String, Long>()

		private final TestProxy rootTestProxy

		TestProxyUpdater(TestProxy rootTestProxy) {
			this.rootTestProxy = rootTestProxy
		}

		def running(String className, String methodName, long now = System.currentTimeMillis()) {
			def classTestProxy = testProxyByClassName.get(className)
			if (!rootTestProxy.children.contains(classTestProxy)) {
				rootTestProxy.addChild(classTestProxy)
				classTestProxy.setState(runningState)
				testStartTimeByClassName.put(className, now)
			}

			def methodTestProxy = testProxyByMethodName.get(methodName)
			if (!classTestProxy.children.contains(methodTestProxy)) {
				classTestProxy.addChild(methodTestProxy)
				methodTestProxy.setState(runningState)
				testStartTimeByMethodName.put(methodName, now)
			}
		}

		def passed(String methodName, long now = System.currentTimeMillis()) {
			testProxyByMethodName.get(methodName).state = passedState
			testProxyByMethodName.get(methodName).statistics = statisticsWithDuration((int) now - testStartTimeByMethodName.get(methodName))
		}

		def failed(String methodName, String error, long now = System.currentTimeMillis()) {
			testProxyByMethodName.get(methodName).state = newTestState(FAILED_INDEX, error)
			testProxyByMethodName.get(methodName).statistics = statisticsWithDuration((int) now - testStartTimeByMethodName.get(methodName))
		}

		def error(String methodName, String error, long now = System.currentTimeMillis()) {
			testProxyByMethodName.get(methodName).state = newTestState(ERROR_INDEX, error)
			testProxyByMethodName.get(methodName).statistics = statisticsWithDuration((int) now - testStartTimeByMethodName.get(methodName))
		}

		def ignored(String methodName) {
			testProxyByMethodName.get(methodName).state = ignoredState
		}

		def finishedClass(String className, long now = System.currentTimeMillis()) {
			def testProxy = testProxyByClassName.get(className)
			def hasChildWith = { int state -> testProxy.children.any{ it.state.magnitude == state } }

			if (hasChildWith(FAILED_INDEX)) testProxy.state = failedState
			else if (hasChildWith(ERROR_INDEX)) testProxy.state = errorState
			else testProxy.state = passedState

			testProxy.statistics = statisticsWithDuration((int) now - testStartTimeByClassName.get(className))
		}

		def finished() {
			def hasChildWith = { state -> rootTestProxy.children.any{ it.state.magnitude == state } }

			if (hasChildWith(FAILED_INDEX)) rootTestProxy.state = failedState
			else if (hasChildWith(ERROR_INDEX)) rootTestProxy.state = errorState
			else rootTestProxy.state = passedState
		}

		private static Statistics statisticsWithDuration(int testMethodDuration) {
			new Statistics() {
				@Override int getTime() { testMethodDuration }
			}
		}

		private static TestState newTestState(int state, String message = null, boolean isFinal = true) {
			new TestState() {
				@Override int getMagnitude() { state }
				@Override boolean isFinal() { isFinal }
				@Override void printOn(Printer printer) {
					if (message != null) {
						def contentType = (state == FAILED_INDEX || state == ERROR_INDEX) ? ERROR_OUTPUT : NORMAL_OUTPUT
						printer.print(message, contentType)
					}
				}
			}
		}

		private static TestInfo newTestInfo(String name, String comment = "") { // TODO use this method in JUnitPanel
			new TestInfo() {
				@Override String getName() { name }
				@Override String getComment() { comment }
				@Override void readFrom(ObjectReader objectReader) {}
				@Override Location getLocation(Project project) { null }
			}
		}
	}

	private static class AppendAdditionalActions {
		private final Project project
		private final String consoleTitle
		RunContentDescriptor descriptor

		AppendAdditionalActions(Project project, String consoleTitle) {
			this.project = project
			this.consoleTitle = consoleTitle
		}

		def appendTo(DefaultActionGroup actionGroup, ConsoleView console, JComponent jComponent) {
			descriptor = new RunContentDescriptor(console, null, jComponent, consoleTitle) {
				@Override boolean isContentReuseProhibited() { true }
				@Override Icon getIcon() { AllIcons.Nodes.Plugin }
			}
			def closeAction = new CloseAction(DefaultRunExecutor.runExecutorInstance, descriptor, project)

			actionGroup.addSeparator()
			actionGroup.addAction(closeAction)
		}
	}

	/**
	 * Copy-pasted com.intellij.execution.junit2.ui.JUnitTreeConsoleView in attempt to "reconfigure" ConsolePanel
	 */
	private static class MyTreeConsoleView extends BaseTestsOutputConsoleView {
		private ConsolePanel myConsolePanel;
		private final JUnitConsoleProperties myProperties;
		private final ExecutionEnvironment myEnvironment;
		private final AppendAdditionalActions appendAdditionalActions

		public MyTreeConsoleView(final JUnitConsoleProperties properties,
		                         final ExecutionEnvironment environment,
		                         final AbstractTestProxy unboundOutputRoot,
		                         AppendAdditionalActions appendAdditionalActions) {
			super(properties, unboundOutputRoot);
			myProperties = properties;
			myEnvironment = environment;
			this.appendAdditionalActions = appendAdditionalActions
		}

		protected TestResultsPanel createTestResultsPanel() {
			def runnerSettings2 = (RunnerSettings) myEnvironment.getRunnerSettings()
			def configurationSettings2 = (ConfigurationPerRunnerSettings) myEnvironment.configurationSettings

			myConsolePanel = new ConsolePanel(getConsole().getComponent(), getPrinter(), myProperties, runnerSettings2, configurationSettings2, getConsole().createConsoleActions()) {
				@Override protected ToolbarPanel createToolbarPanel() {

					return new JUnitToolbarPanel(myProperties, runnerSettings2, configurationSettings2, this) {
						@Override protected void appendAdditionalActions(DefaultActionGroup defaultActionGroup, TestConsoleProperties testConsoleProperties,
						                                                 RunnerSettings runnerSettings, ConfigurationPerRunnerSettings configurationPerRunnerSettings, JComponent jComponent) {
							super.appendAdditionalActions(defaultActionGroup, testConsoleProperties, runnerSettings, configurationPerRunnerSettings, jComponent)

							MyTreeConsoleView.this.appendAdditionalActions.appendTo(defaultActionGroup, getConsole(), jComponent)
						}
					}
				}
			}
			return myConsolePanel;
		}

		public void attachToProcess(final ProcessHandler processHandler) {
			super.attachToProcess(processHandler);
			myConsolePanel.onProcessStarted(processHandler);
		}

		public void dispose() {
			super.dispose();
			myConsolePanel = null;
		}

		@Override
		public JComponent getPreferredFocusableComponent() {
			return myConsolePanel.getTreeView();
		}

		public void attachToModel(@NotNull JUnitRunningModel model) {
			if (myConsolePanel != null) {
				myConsolePanel.getTreeView().attachToModel(model);
				model.attachToTree(myConsolePanel.getTreeView());
				myConsolePanel.setModel(model);
				model.onUIBuilt();
				new TreeCollapser().setModel(model);
			}
		}
	}

}