/*
 * Copyright (C) 2019-2020 Vera Clemens, Tom Kranz, Tom Heimbrodt, Elias Kuiter
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.scionlab.endhost.scion;

import android.app.Service;

import com.moandjiezana.toml.Toml;

import org.json.JSONException;
import org.json.JSONObject;
import org.rauschig.jarchivelib.ArchiveFormat;
import org.rauschig.jarchivelib.ArchiverFactory;
import org.rauschig.jarchivelib.CompressionType;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import timber.log.Timber;

import static org.scionlab.endhost.scion.Config.Scion.*;

public class Scion {
    private final Service service;
    private final Storage storage;
    private final ComponentRegistry componentRegistry;
    private Scmp scmp;

    public enum State {
        STOPPED, STARTING, HEALTHY, UNHEALTHY
    }

    public enum Version {
        V0_4_0(V0_4_0_BINARY_PATH),
        SCIONLAB(SCIONLAB_BINARY_PATH);

        private String binaryPath;

        Version(String binaryPath) {
            this.binaryPath = binaryPath;
        }

        public String getBinaryPath() {
            return binaryPath;
        }
    }

    public Scion(Service service, BiConsumer<State, Map<String, Scion.State>> stateCallback) {
        this.service = service;
        Process.initialize(service);
        storage = Storage.from(service);
        componentRegistry = new ComponentRegistry(storage,
                (Map<String, Scion.State> componentState) ->
                        stateCallback.accept(getState(), componentState));
    }

    public void start(Version version, String scionlabArchiveFile) {
        try {
            ArchiverFactory.createArchiver(ArchiveFormat.TAR, CompressionType.GZIP)
                    .extract(new File(scionlabArchiveFile), storage.getFile(TMP_DIRECTORY_PATH));
            start(version,
                    storage.getAbsolutePath(TMP_GEN_DIRECTORY_PATH),
                    storage.getAbsolutePath(TMP_VPN_CONFIG_PATH));
            storage.deleteFileOrDirectory(TMP_DIRECTORY_PATH);
        } catch (IOException e) {
            Timber.e(e);
        }
    }

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    public void start(Version version, String genDirectory, String vpnConfigFile) {
        // copy gen directory provided by the user
        if (storage.countFilesInDirectory(new File(genDirectory)) > GEN_DIRECTORY_FILE_LIMIT) {
            Timber.e("too many files in gen directory, did you choose the right directory?");
            return;
        }
        storage.deleteFileOrDirectory(GEN_DIRECTORY_PATH);
        storage.copyFileOrDirectory(new File(genDirectory), GEN_DIRECTORY_PATH);

        Optional<String> isdPath = storage.findInDirectory(GEN_DIRECTORY_PATH, ISD_DIRECTORY_PATH_REGEX);
        Optional<String> asPath = storage.findInDirectory(isdPath, AS_DIRECTORY_PATH_REGEX);
        Optional<String> componentPath = storage.findInDirectory(asPath, COMPONENT_DIRECTORY_PATH_REGEX);
        Optional<String> endhostPath = storage.findInDirectory(asPath, ENDHOST_DIRECTORY_PATH_REGEX);
        Optional<String> certsPath = storage.findInDirectory(componentPath, CERTS_DIRECTORY_PATH_REGEX);
        Optional<String> keysPath = storage.findInDirectory(componentPath, KEYS_DIRECTORY_PATH_REGEX);
        Optional<String> topologyPath = storage.findInDirectory(componentPath, TOPOLOGY_PATH_REGEX);
        Optional<String> daemonConfigPath = storage.findInDirectory(endhostPath, DAEMON_CONFIG_PATH_REGEX);

        if (!Stream.of(isdPath, asPath, componentPath, endhostPath, certsPath, keysPath,
                topologyPath, daemonConfigPath).allMatch(Optional::isPresent)) {
            Timber.e("unexpected gen directory structure");
            return;
        }

        storage.createDirectory(CONFIG_DIRECTORY_PATH);
        storage.copyFileOrDirectory(certsPath.get(), CERTS_DIRECTORY_PATH);
        storage.copyFileOrDirectory(keysPath.get(), KEYS_DIRECTORY_PATH);
        if (!writeTopology(topologyPath.get()))
            return;
        String publicAddress = readDaemonConfig(daemonConfigPath.get());
        storage.deleteFileOrDirectory(GEN_DIRECTORY_PATH);

        Timber.i("starting SCION components");
        componentRegistry
                .setBinaryPath(version.getBinaryPath())
                .start(new VPNClient(service, storage.readFile(new File(vpnConfigFile))))
                .start(new BeaconServer())
                .start(new BorderRouter())
                .start(new CertificateServer())
                .start(new Dispatcher())
                .start(new Daemon(publicAddress))
                .start(new PathServer())
                .start(scmp = new Scmp())
                .notifyStateChange();
    }

    public void stop() {
        Timber.i("stopping SCION components");
        componentRegistry.stopAll().notifyStateChange();
        scmp = null;
    }

    public State getState() {
        if (!componentRegistry.hasRegisteredComponents())
            return State.STOPPED;
        if (componentRegistry.hasStartingComponents())
            return State.STARTING;
        if (scmp != null && scmp.isHealthy())
            return State.HEALTHY;
        return State.UNHEALTHY;
    }

    private String readDaemonConfig(String daemonConfigPath) {
        return new Toml()
                .read(storage.getInputStream(daemonConfigPath))
                .getString(DAEMON_CONFIG_PUBLIC_TOML_PATH);
    }

    private boolean writeTopology(String topologyPath) {
        try {
            JSONObject root = new JSONObject(storage.readFile(topologyPath));
            JSONObject borderRouters = root.getJSONObject(BORDER_ROUTERS_JSON_PATH);
            JSONObject interfaces = borderRouters.getJSONObject(borderRouters.keys().next()).getJSONObject(INTERFACES_JSON_PATH);
            JSONObject iface = interfaces.getJSONObject(interfaces.keys().next());
            String ia = root.getString(IA_JSON_PATH);
            String overlayAddr = iface.getJSONObject(PUBLIC_OVERLAY_JSON_PATH).getString(OVERLAY_ADDR_JSON_PATH);
            int overlayPort = iface.getJSONObject(PUBLIC_OVERLAY_JSON_PATH).getInt(OVERLAY_PORT_JSON_PATH);
            String remoteIa = iface.getString(IA_JSON_PATH);
            String remoteOverlayAddr = iface.getJSONObject(REMOTE_OVERLAY_JSON_PATH).getString(OVERLAY_ADDR_JSON_PATH);
            int remoteOverlayPort = iface.getJSONObject(REMOTE_OVERLAY_JSON_PATH).getInt(OVERLAY_PORT_JSON_PATH);
            storage.writeFile(TOPOLOGY_PATH, String.format(storage.readAssetFile(TOPOLOGY_TEMPLATE_PATH),
                    remoteIa, overlayAddr, overlayPort,
                    remoteOverlayAddr, remoteOverlayPort, ia));
        } catch (JSONException e) {
            Timber.e(e);
            return false;
        }
        return true;
    }
}
