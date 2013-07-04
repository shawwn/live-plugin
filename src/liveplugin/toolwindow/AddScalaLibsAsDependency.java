package liveplugin.toolwindow;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.util.Pair;
import com.intellij.util.Function;
import liveplugin.toolwindow.util.DependenciesUtil;

import java.util.List;

import static com.intellij.openapi.roots.OrderRootType.CLASSES;
import static com.intellij.openapi.util.Pair.create;
import static com.intellij.util.containers.ContainerUtil.map;
import static liveplugin.LivePluginAppComponent.LIVEPLUGIN_LIBS_PATH;
import static liveplugin.LivePluginAppComponent.scalaIsOnClassPath;
import static liveplugin.MyFileUtil.fileNamesMatching;

class AddScalaLibsAsDependency extends AnAction {
	private static final String LIBRARY_NAME = "LivePlugin - Scala";

	@Override public void actionPerformed(AnActionEvent event) {
		Project project = event.getProject();
		if (project == null) return;

		if (DependenciesUtil.allModulesHasLibraryAsDependencyIn(project, LIBRARY_NAME)) {
			DependenciesUtil.removeLibraryDependencyFrom(project, LIBRARY_NAME);
		} else {
			List<Pair<String, OrderRootType>> paths = map(fileNamesMatching(DownloadScalaLibs.LIB_FILES_PATTERN, LIVEPLUGIN_LIBS_PATH), new Function<String, Pair<String, OrderRootType>>() {
				@Override public Pair<String, OrderRootType> fun(String fileName) {
					return create("jar://" + LIVEPLUGIN_LIBS_PATH + fileName + "!/", CLASSES);
				}
			});
			DependenciesUtil.addLibraryDependencyTo(project, LIBRARY_NAME, paths);
		}
	}

	@Override public void update(AnActionEvent event) {
		Project project = event.getProject();
		if (project == null) return;

		if (DependenciesUtil.allModulesHasLibraryAsDependencyIn(project, LIBRARY_NAME)) {
			event.getPresentation().setText("Remove Scala Libraries from Project");
			event.getPresentation().setDescription("Remove Scala Libraries from Project");
		} else {
			event.getPresentation().setText("Add Scala Libraries to Project");
			event.getPresentation().setDescription("Add Scala Libraries to Project");
			event.getPresentation().setEnabled(scalaIsOnClassPath());
		}
	}
}