/**
 *  StatusServlet
 *  Copyright 27.02.2015 by Michael Peter Christen, @0rb1t3r
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

package org.loklak.api.server;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.search.sort.SortOrder;
import org.loklak.Caretaker;
import org.loklak.api.client.StatusClient;
import org.loklak.data.DAO;
import org.loklak.data.QueryEntry;
import org.loklak.http.RemoteAccess;
import org.loklak.tools.OS;

public class StatusServlet extends HttpServlet {
   
    private static final long serialVersionUID = 8578478303032749879L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGet(request, response);
    }
    
    @SuppressWarnings("unchecked")
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        RemoteAccess.Post post = RemoteAccess.evaluate(request);
        
        String callback = post.get("callback", "");
        boolean jsonp = callback != null && callback.length() > 0;
        
        if (post.isLocalhostAccess() && OS.canExecUnix && post.get("upgrade", "").equals("true")) {
            Caretaker.upgrade(); // it's a hack to add this here, this may disappear anytime
        }
        
        final String backend = DAO.getConfig("backend", "");
        final boolean backend_push = DAO.getConfig("backend.push.enabled", false);
        Map<String, Object> backend_status = null;
        Map<String, Object> backend_status_index_sizes = null;
        if (backend.length() > 0 && !backend_push) {
            backend_status = StatusClient.status(backend);
            backend_status_index_sizes = backend_status == null ? null : (Map<String, Object>) backend_status.get("index_sizes");
        }
        long backend_messages = backend_status_index_sizes == null ? 0 : ((Number) backend_status_index_sizes.get("messages")).longValue();
        long backend_users = backend_status_index_sizes == null ? 0 : ((Number) backend_status_index_sizes.get("users")).longValue();
        long local_messages = DAO.countLocalMessages(-1);
        long local_users = DAO.countLocalUsers();
        
        post.setResponse(response, "application/javascript");
        
        // generate json
        XContentBuilder json = XContentFactory.jsonBuilder().prettyPrint().lfAtEnd();
        json.startObject();

        Runtime runtime = Runtime.getRuntime();
        json.field("system");
        json.startObject();
        json.field("assigned_memory", runtime.maxMemory());
        json.field("used_memory", runtime.totalMemory() - runtime.freeMemory());
        json.field("available_memory", runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory());
        json.field("cores", runtime.availableProcessors());
        json.field("threads", Thread.activeCount());
        json.field("runtime", System.currentTimeMillis() - Caretaker.startupTime);
        json.field("time_to_restart", Caretaker.upgradeTime - System.currentTimeMillis());
        json.field("load_system_average", OS.getSystemLoadAverage());
        json.field("load_system_cpu", OS.getSystemCpuLoad());
        json.field("load_process_cpu", OS.getProcessCpuLoad());
        json.endObject(); // of system
        
        json.field("index_sizes");
        json.startObject();
        json.field("messages", local_messages + backend_messages);
        json.field("messages_local", local_messages);
        json.field("messages_backend", backend_messages);
        json.field("mps", Math.max(DAO.countLocalMessages(86400000) / 86400, DAO.countLocalMessages(600000) / 600)); // best of 24h and 10m
        json.field("users", local_users + backend_users);
        json.field("users_local", local_users);
        json.field("users_backend", backend_users);
        json.field("queries", DAO.countLocalQueries());
        json.field("accounts", DAO.countLocalAccounts());
        json.field("user", DAO.user_dump.size());
        json.field("followers", DAO.followers_dump.size());
        json.field("following", DAO.following_dump.size());
        if (DAO.getConfig("retrieval.queries.enabled", false)) {
            List<QueryEntry> queryList = DAO.SearchLocalQueries("", 1000, "retrieval_next", "date", SortOrder.ASC, null, new Date(), "retrieval_next");
            json.field("queries_pending", queryList.size());
        }
        json.endObject(); // of index_sizes
        
        json.field("client_info");
        json.startObject();
        json.field("RemoteHost", post.getClientHost());
        json.field("IsLocalhost", post.isLocalhostAccess() ? "true" : "false");
        Enumeration<String> he = request.getHeaderNames();
        while (he.hasMoreElements()) {
            String h = he.nextElement();
            json.field(h, request.getHeader(h));
        }
        json.endObject(); // of client_info
        
        json.endObject(); // of root object

        // write json
        response.setCharacterEncoding("UTF-8");
        PrintWriter sos = response.getWriter();
        if (jsonp) sos.print(callback + "(");
        sos.print(json.string());
        if (jsonp) sos.println(");");
        sos.println();
        post.finalize();
    }

    
}
