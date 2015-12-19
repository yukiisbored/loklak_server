/**
 *  HelloClient
 *  Copyright 22.02.2015 by Michael Peter Christen, @0rb1t3r
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *  
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package org.loklak.api.client;

import java.io.IOException;

import org.loklak.data.DAO;
import org.loklak.http.ClientConnection;

public class HelloClient {

    public static void propagate(final String[] hoststubs) {
        int httpport = (int) DAO.getConfig("port.http", 9000);
        int httpsport = (int) DAO.getConfig("port.https", 9443);
        String peername = (String) DAO.getConfig("peername", "anonymous");
        for (String hoststub: hoststubs) {
            if (hoststub.endsWith("/")) hoststub = hoststub.substring(0, hoststub.length() - 1);
            ClientConnection connection = null;
            try {
                connection = new ClientConnection(hoststub + "/api/hello.json?port.http=" + httpport + "&port.https=" + httpsport + "&peername=" + peername);
            } catch (IOException e) {
            } finally {
                if (connection != null) connection.close();
            }
        }
    }
}
