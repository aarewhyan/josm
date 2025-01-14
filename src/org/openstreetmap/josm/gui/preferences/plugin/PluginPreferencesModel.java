// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.gui.preferences.plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.openstreetmap.josm.gui.util.ChangeNotifier;
import org.openstreetmap.josm.plugins.PluginException;
import org.openstreetmap.josm.plugins.PluginHandler;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

/**
 * The plugin model behind a {@code PluginListPanel}.
 */
public class PluginPreferencesModel extends ChangeNotifier {
    // remember the initial list of active plugins
    private final Set<String> currentActivePlugins;
    private final List<PluginInformation> availablePlugins = new ArrayList<>();
    private PluginInstallation filterStatus;
    private String filterExpression;
    private final List<PluginInformation> displayedPlugins = new ArrayList<>();
    private final Map<PluginInformation, Boolean> selectedPluginsMap = new HashMap<>();
    // plugins that still require an update/download
    private final Set<String> pendingDownloads = new HashSet<>();

    /**
     * Constructs a new {@code PluginPreferencesModel}.
     */
    public PluginPreferencesModel() {
        currentActivePlugins = new HashSet<>();
        currentActivePlugins.addAll(Config.getPref().getList("plugins"));
    }

    /**
     * Filters the list of displayed plugins by installation status.
     * @param status The filter used against installation status
     * @since 13799
     */
    public void filterDisplayedPlugins(PluginInstallation status) {
        filterStatus = status;
        doFilter();
    }

    /**
     * Filters the list of displayed plugins by text.
     * @param filter The filter used against plugin name, description or version
     */
    public void filterDisplayedPlugins(String filter) {
        filterExpression = filter;
        doFilter();
    }

    private void doFilter() {
        displayedPlugins.clear();
        for (PluginInformation pi: availablePlugins) {
            if ((filterStatus == null || matchesInstallationStatus(pi))
             && (filterExpression == null || pi.matches(filterExpression))) {
                displayedPlugins.add(pi);
            }
        }
        fireStateChanged();
    }

    private boolean matchesInstallationStatus(PluginInformation pi) {
        boolean installed = currentActivePlugins.contains(pi.getName());
        return PluginInstallation.ALL == filterStatus
           || (PluginInstallation.INSTALLED == filterStatus && installed)
           || (PluginInstallation.AVAILABLE == filterStatus && !installed);
    }

    /**
     * Sets the list of available plugins.
     * @param available The available plugins
     */
    public void setAvailablePlugins(Collection<PluginInformation> available) {
        availablePlugins.clear();
        if (available != null) {
            availablePlugins.addAll(available);
        }
        availablePluginsModified();
    }

    protected final void availablePluginsModified() {
        sort();
        filterDisplayedPlugins(filterStatus);
        filterDisplayedPlugins(filterExpression);
        Set<String> activePlugins = new HashSet<>();
        activePlugins.addAll(Config.getPref().getList("plugins"));
        for (PluginInformation pi: availablePlugins) {
            if (selectedPluginsMap.get(pi) == null && activePlugins.contains(pi.name)) {
                selectedPluginsMap.put(pi, Boolean.TRUE);
            }
        }
        fireStateChanged();
    }

    protected void updateAvailablePlugin(PluginInformation other) {
        if (other != null) {
            PluginInformation pi = getPluginInformation(other.name);
            if (pi == null) {
                availablePlugins.add(other);
                return;
            }
            pi.updateFromPluginSite(other);
        }
    }

    /**
     * Updates the list of plugin information objects with new information from
     * plugin update sites.
     *
     * @param fromPluginSite plugin information read from plugin update sites
     */
    public void updateAvailablePlugins(Collection<PluginInformation> fromPluginSite) {
        for (PluginInformation other: fromPluginSite) {
            updateAvailablePlugin(other);
        }
        availablePluginsModified();
    }

    /**
     * Replies the list of selected plugin information objects
     *
     * @return the list of selected plugin information objects
     */
    public List<PluginInformation> getSelectedPlugins() {
        return availablePlugins.stream()
                .filter(pi -> Boolean.TRUE.equals(selectedPluginsMap.get(pi)))
                .collect(Collectors.toList());
    }

    /**
     * Replies the list of selected plugin information objects
     *
     * @return the list of selected plugin information objects
     */
    public Set<String> getSelectedPluginNames() {
        return getSelectedPlugins().stream().map(pi -> pi.name).collect(Collectors.toSet());
    }

    /**
     * Sorts the list of available plugins
     */
    protected void sort() {
        availablePlugins.sort(Comparator.comparing(
                o -> o.getName() == null ? "" : o.getName().toLowerCase(Locale.ENGLISH)));
    }

    /**
     * Replies the list of plugin information to display.
     *
     * @return the list of plugin information to display
     */
    public List<PluginInformation> getDisplayedPlugins() {
        return displayedPlugins;
    }

    /**
     * Replies the set of plugins waiting for update or download.
     *
     * @return the set of plugins waiting for update or download
     */
    public Set<PluginInformation> getPluginsScheduledForUpdateOrDownload() {
        return pendingDownloads.stream()
                .map(this::getPluginInformation)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * Sets whether the plugin is selected or not.
     *
     * @param name the name of the plugin
     * @param selected true, if selected; false, otherwise
     */
    public void setPluginSelected(String name, boolean selected) {
        PluginInformation pi = getPluginInformation(name);
        if (pi != null) {
            selectedPluginsMap.put(pi, selected);
            if (pi.isUpdateRequired()) {
                pendingDownloads.add(pi.name);
            }
        }
        if (!selected) {
            pendingDownloads.remove(name);
        }
    }

    /**
     * Removes all the plugin in {@code plugins} from the list of plugins
     * with a pending download
     *
     * @param plugins the list of plugins to clear for a pending download
     */
    public void clearPendingPlugins(Collection<PluginInformation> plugins) {
        if (plugins != null) {
            for (PluginInformation pi: plugins) {
                pendingDownloads.remove(pi.name);
            }
        }
    }

    /**
     * Replies the plugin info with the name <code>name</code>. null, if no
     * such plugin info exists.
     *
     * @param name the name. If null, replies null.
     * @return the plugin info.
     */
    public PluginInformation getPluginInformation(String name) {
        if (name != null) {
            return availablePlugins.stream()
                    .filter(pi -> name.equals(pi.getName()) || name.equals(pi.provides))
                    .findFirst().orElse(null);
        }
        return null;
    }

    /**
     * Initializes the model from preferences
     */
    public void initFromPreferences() {
        Collection<String> enabledPlugins = Config.getPref().getList("plugins", null);
        if (enabledPlugins == null) {
            this.selectedPluginsMap.clear();
            return;
        }
        for (String name: enabledPlugins) {
            PluginInformation pi = getPluginInformation(name);
            if (pi == null) {
                continue;
            }
            setPluginSelected(name, true);
        }
    }

    /**
     * Replies true if the plugin with name <code>name</code> is currently
     * selected in the plugin model
     *
     * @param name the plugin name
     * @return true if the plugin is selected; false, otherwise
     */
    public boolean isSelectedPlugin(String name) {
        PluginInformation pi = getPluginInformation(name);
        if (pi == null || selectedPluginsMap.get(pi) == null)
            return false;
        return selectedPluginsMap.get(pi);
    }

    /**
     * Replies the set of plugins which have been added by the user to
     * the set of activated plugins.
     *
     * @return the set of newly activated plugins
     */
    public List<PluginInformation> getNewlyActivatedPlugins() {
        List<PluginInformation> ret = new LinkedList<>();
        for (Entry<PluginInformation, Boolean> entry: selectedPluginsMap.entrySet()) {
            PluginInformation pi = entry.getKey();
            boolean selected = entry.getValue();
            if (selected && !currentActivePlugins.contains(pi.name)) {
                ret.add(pi);
            }
        }
        return ret;
    }

    /**
     * Replies the set of plugins which have been removed by the user from
     * the set of deactivated plugins.
     *
     * @return the set of newly deactivated plugins
     */
    public List<PluginInformation> getNewlyDeactivatedPlugins() {
        return availablePlugins.stream()
                .filter(pi -> currentActivePlugins.contains(pi.name))
                .filter(pi -> selectedPluginsMap.get(pi) == null || !selectedPluginsMap.get(pi))
                .collect(Collectors.toList());
    }

    /**
     * Replies the set of all available plugins.
     *
     * @return the set of all available plugins
     */
    public List<PluginInformation> getAvailablePlugins() {
        return new LinkedList<>(availablePlugins);
    }

    /**
     * Replies the set of plugin names which have been added by the user to
     * the set of activated plugins.
     *
     * @return the set of newly activated plugin names
     */
    public Set<String> getNewlyActivatedPluginNames() {
        return getNewlyActivatedPlugins().stream().map(pi -> pi.name).collect(Collectors.toSet());
    }

    /**
     * Replies true if the set of active plugins has been changed by the user
     * in this preference model. He has either added plugins or removed plugins
     * being active before.
     *
     * @return true if the collection of active plugins has changed
     */
    public boolean isActivePluginsChanged() {
        Set<String> newActivePlugins = getSelectedPluginNames();
        return !newActivePlugins.equals(currentActivePlugins);
    }

    /**
     * Refreshes the local version field on the plugins in <code>plugins</code> with
     * the version in the manifest of the downloaded "jar.new"-file for this plugin.
     *
     * @param plugins the collections of plugins to refresh
     */
    public void refreshLocalPluginVersion(Collection<PluginInformation> plugins) {
        if (plugins != null) {
            for (PluginInformation pi : plugins) {
                File downloadedPluginFile = PluginHandler.findUpdatedJar(pi.name);
                if (downloadedPluginFile == null) {
                    continue;
                }
                try {
                    PluginInformation newinfo = new PluginInformation(downloadedPluginFile, pi.name);
                    PluginInformation oldinfo = getPluginInformation(pi.name);
                    if (oldinfo != null) {
                        oldinfo.updateFromJar(newinfo);
                    }
                } catch (PluginException e) {
                    Logging.error(e);
                }
            }
        }
    }
}
