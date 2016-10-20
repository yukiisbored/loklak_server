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

package org.loklak.api.admin;

import java.io.IOException;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.elasticsearch.search.sort.SortOrder;
import org.json.JSONObject;
import org.loklak.Caretaker;
import org.loklak.LoklakServer;
import org.loklak.data.DAO;
import org.loklak.data.IncomingMessageBuffer;
import org.loklak.http.ClientConnection;
import org.loklak.objects.QueryEntry;
import org.loklak.server.APIException;
import org.loklak.server.APIHandler;
import org.loklak.server.AbstractAPIHandler;
import org.loklak.server.Authorization;
import org.loklak.server.BaseUserRole;
import org.loklak.server.Query;
import org.loklak.tools.OS;
import org.loklak.tools.UTF8;
import org.loklak.tools.storage.JSONObjectWithDefault;

public class StatusService extends AbstractAPIHandler implements APIHandler {
   
    private static final long serialVersionUID = 8578478303032749879L;

    @Override
    public String getAPIPath() {
        return "/api/status.json";
    }

    @Override
    public BaseUserRole getMinimalBaseUserRole() {
        return BaseUserRole.ANONYMOUS;
    }

    @Override
    public JSONObject getDefaultPermissions(BaseUserRole baseUserRole) {
        return null;
    }


    public static JSONObject status(final String protocolhostportstub) throws IOException {
        final String urlstring = protocolhostportstub + "/api/status.json";
        byte[] response = ClientConnection.downloadPeer(urlstring);
        if (response == null || response.length == 0) return new JSONObject();
        JSONObject json = new JSONObject(UTF8.String(response));
        return json;
    }

    @Override
    public JSONObject serviceImpl(Query post, HttpServletResponse response, Authorization rights, JSONObjectWithDefault permissions) throws APIException {

        if (post.isLocalhostAccess() && OS.canExecUnix && post.get("upgrade", "").equals("true")) {
            Caretaker.upgrade(); // it's a hack to add this here, this may disappear anytime
        }
        
        final String backend = DAO.getConfig("backend", "");
        final boolean backend_push = DAO.getConfig("backend.push.enabled", false);
        JSONObject backend_status = null;
        JSONObject backend_status_index_sizes = null;
        if (backend.length() > 0 && !backend_push) try {
            backend_status = StatusService.status(backend);
            backend_status_index_sizes = backend_status == null ? null : (JSONObject) backend_status.get("index_sizes");
        } catch (IOException e) {}
        long backend_messages = backend_status_index_sizes == null ? 0 : ((Number) backend_status_index_sizes.get("messages")).longValue();
        long backend_users = backend_status_index_sizes == null ? 0 : ((Number) backend_status_index_sizes.get("users")).longValue();
        long local_messages = DAO.countLocalMessages();
        long local_users = DAO.countLocalUsers();
        
        post.setResponse(response, "application/javascript");
        
        // generate json
        Runtime runtime = Runtime.getRuntime();
        JSONObject json = new JSONObject(true);
        JSONObject system = new JSONObject(true);
        system.put("assigned_memory", runtime.maxMemory());
        system.put("used_memory", runtime.totalMemory() - runtime.freeMemory());
        system.put("available_memory", runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory());
        system.put("cores", runtime.availableProcessors());
        system.put("threads", Thread.activeCount());
        system.put("runtime", System.currentTimeMillis() - Caretaker.startupTime);
        system.put("time_to_restart", Caretaker.upgradeTime - System.currentTimeMillis());
        system.put("load_system_average", OS.getSystemLoadAverage());
        system.put("load_system_cpu", OS.getSystemCpuLoad());
        system.put("load_process_cpu", OS.getProcessCpuLoad());
        system.put("server_threads", LoklakServer.getServerThreads());
        system.put("server_uri", LoklakServer.getServerURI());

        JSONObject index = new JSONObject(true);
        long countLocalMinMessages  = DAO.countLocalMessages(60000L);
        long countLocal10MMessages  = DAO.countLocalMessages(600000L);
        long countLocalHourMessages = DAO.countLocalMessages(3600000L);
        long countLocalDayMessages  = DAO.countLocalMessages(86400000L);
        long countLocalWeekMessages = DAO.countLocalMessages(604800000L);
        float mps1m  = countLocalMinMessages  / 60f;
        float mps10m = countLocal10MMessages  / 600f;
        float mps1h  = countLocalHourMessages / 3600f;
        float mps1d  = countLocalDayMessages  / 86400f;
        float mps1w  = countLocalWeekMessages / 604800f;
        index.put("mps1m", mps1m);
        index.put("mps10m", mps10m);
        index.put("mps1h", mps1h);
        index.put("mps1d", mps1d);
        index.put("mps1w", mps1w);
        index.put("mps", (int) Math.max(mps1d, Math.max(mps1h, Math.max(mps10m, mps1m)))); // best of 1d, 1h and 10m
        JSONObject messages = new JSONObject(true);
        messages.put("size", local_messages + backend_messages);
        messages.put("size_local", local_messages);
        messages.put("size_local_minute", countLocalMinMessages);
        messages.put("size_local_10minutes", countLocal10MMessages);
        messages.put("size_local_hour", countLocalHourMessages);
        messages.put("size_local_day", countLocalDayMessages);
        messages.put("size_local_week", countLocalWeekMessages);
        messages.put("size_backend", backend_messages);
        messages.put("stats", DAO.messages.getStats());
        JSONObject queue = new JSONObject(true);
        queue.put("size", IncomingMessageBuffer.getMessageQueueSize());
        queue.put("maxSize", IncomingMessageBuffer.getMessageQueueMaxSize());
        queue.put("clients", IncomingMessageBuffer.getMessageQueueClients());   
        messages.put("queue", queue);
        JSONObject users = new JSONObject(true);
        users.put("size", local_users + backend_users);
        users.put("size_local", local_users);
        users.put("size_backend", backend_users);
        users.put("stats", DAO.users.getStats());
        JSONObject queries = new JSONObject(true);
        queries.put("size", DAO.countLocalQueries());
        queries.put("stats", DAO.queries.getStats());
        JSONObject accounts = new JSONObject(true);
        accounts.put("size", DAO.countLocalAccounts());
        JSONObject user = new JSONObject(true);
        user.put("size", DAO.user_dump.size());
        JSONObject followers = new JSONObject(true);
        followers.put("size", DAO.followers_dump.size());
        JSONObject following = new JSONObject(true);
        following.put("size", DAO.following_dump.size());
        index.put("messages", messages);
        index.put("users", users);
        index.put("queries", queries);
        index.put("accounts", accounts);
        index.put("user", user);
        index.put("followers", followers);
        index.put("following", following);
        if (DAO.getConfig("retrieval.queries.enabled", false)) {
            List<QueryEntry> queryList = DAO.SearchLocalQueries("", 1000, "retrieval_next", "date", SortOrder.ASC, null, new Date(), "retrieval_next");
            index.put("queries_pending", queryList.size());
        } 
        
        JSONObject client_info = new JSONObject(true);
        client_info.put("RemoteHost", post.getClientHost());
        client_info.put("IsLocalhost", post.isLocalhostAccess() ? "true" : "false");

        JSONObject request_header = new JSONObject(true);
        Enumeration<String> he = post.getRequest().getHeaderNames();
        while (he.hasMoreElements()) {
            String h = he.nextElement();
            request_header.put(h, post.getRequest().getHeader(h));
        }
        client_info.put("request_header", request_header);
        
        json.put("system", system);
        json.put("index", index);
        json.put("client_info", client_info);

        return json;
    }

    public static void main(String[] args) {
        try {
            JSONObject json = status("http://loklak.org");
            JSONObject index_sizs = (JSONObject) json.get("index_sizes");
            System.out.println(json.toString());
            System.out.println(index_sizs.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
        
    }
    
}
