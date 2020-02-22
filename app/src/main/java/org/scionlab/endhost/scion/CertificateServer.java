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

import org.scionlab.endhost.Logger;

import static org.scionlab.endhost.scion.Config.CertificateServer.*;

public class CertificateServer extends Component {
    private String configDirectoryPath;

    public CertificateServer(String configDirectoryPath) {
        this.configDirectoryPath = configDirectoryPath;
    }

    @Override
    protected String getTag() {
        return "CertificateServer";
    }

    @Override
    boolean prepare() {
        storage.prepareFiles(TRUST_DATABASE_PATH);
        storage.writeFile(CONFIG_PATH, String.format(
                storage.readAssetFile(CONFIG_TEMPLATE_PATH),
                storage.getAbsolutePath(configDirectoryPath),
                storage.getAbsolutePath(LOG_PATH),
                LOG_LEVEL,
                storage.getAbsolutePath(TRUST_DATABASE_PATH),
                storage.getAbsolutePath(Config.Daemon.RELIABLE_SOCKET_PATH)));
        setupLogThread(LOG_PATH, WATCH_PATTERN);
        return true;
    }

    @Override
    void run() {
        Binary.runCertificateServer(getContext(),
                Logger.createLogThread(getTag()),
                storage.getAbsolutePath(CONFIG_PATH),
                storage.getAbsolutePath(Config.Dispatcher.SOCKET_PATH));
    }
}
