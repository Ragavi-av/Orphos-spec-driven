import { createContext, useContext, useEffect, useState } from 'react';
import { bootstrapGet } from 'eip-shell-context/webBff';
import { useAuth } from 'eip-shell-context/AuthProvider';

const PluginRegistryContext = createContext({ plugins: [], loading: true });

/**
 * Fetches the active plugin manifests for the authenticated tenant from
 * platform-registry-service and makes them available to the whole shell.
 *
 * The manifest list drives: tab visibility, side-panel slots, and which
 * remoteEntry.js URLs to feed into the Module Federation dynamic loader.
 */
export function PluginRegistryProvider({ children }) {
  const keycloak = useAuth();
  const [plugins, setPlugins] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!keycloak?.authenticated) return;
    bootstrapGet('plugins', '/api/platform/plugins/active')
      // Guard against a non-array body (e.g. an error envelope that slipped
      // past axios's status-code check, or a backend contract change) —
      // usePluginTabs/usePluginAdminSections call .flatMap/.find on this
      // unconditionally, which throws on anything but a real array.
      .then(r => setPlugins(Array.isArray(r.data) ? r.data : []))
      .catch(() => setPlugins([]))
      .finally(() => setLoading(false));
  }, [keycloak?.authenticated]);

  return (
    <PluginRegistryContext.Provider value={{ plugins, loading }}>
      {children}
    </PluginRegistryContext.Provider>
  );
}

export function usePlugins() {
  return useContext(PluginRegistryContext);
}

/** Returns all tab definitions contributed by active plugins, in plugin order. */
export function usePluginTabs() {
  const { plugins } = usePlugins();
  return (Array.isArray(plugins) ? plugins : []).flatMap(p =>
    (p.tabs || []).map(tab => ({ ...tab, pluginId: p.pluginId }))
  );
}

/** Returns the plugin manifest for a given pluginId, or undefined. */
export function usePlugin(pluginId) {
  const { plugins } = usePlugins();
  return (Array.isArray(plugins) ? plugins : []).find(p => p.pluginId === pluginId);
}

/** Returns admin section IDs contributed by all active plugins. */
export function usePluginAdminSections() {
  const { plugins } = usePlugins();
  return (Array.isArray(plugins) ? plugins : []).flatMap(p =>
    (p.adminSections || []).map(section => ({ section, pluginId: p.pluginId }))
  );
}
