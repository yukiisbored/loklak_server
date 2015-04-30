/**
 *  SearchServlet
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

package org.loklak.api.server;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.log.Log;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.loklak.api.RemoteAccess;
import org.loklak.data.DAO;
import org.loklak.data.Timeline;
import org.loklak.data.MessageEntry;
import org.loklak.data.UserEntry;
import org.loklak.rss.RSSFeed;
import org.loklak.rss.RSSMessage;
import org.loklak.tools.CharacterCoding;

/**
 * The search servlet. we provide opensearch/rss and twitter-like JSON as result.
 */
public class SearchServlet extends HttpServlet {

    private static final long serialVersionUID = 563533152152063908L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGet(request, response);
    }
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
        RemoteAccess.Post post = RemoteAccess.evaluate(request);
        
        // manage DoS
        if (post.isDoS_blackout()) {response.sendError(503, "your request frequency is too high"); return;}
        
        // check call type
        boolean jsonExt = request.getServletPath().endsWith(".json");

        // evaluate get parameter
        String callback = post.get("callback", "");
        boolean jsonp = callback != null && callback.length() > 0;
        boolean minified = post.get("minified", false);
        String query = post.get("q", "");
        if (query == null || query.length() == 0) query = post.get("query", "");
        query = CharacterCoding.html2unicode(query).replaceAll("\\+", " ");
        int count = post.get("count", post.get("maximumRecords", 100));
        String source = post.isDoS_servicereduction() ? "cache" : post.get("source", "all"); // possible values: cache, backend, twitter, all
        int limit = post.get("limit", 100);
        String[] fields = post.get("fields", new String[0], ",");
        int timezoneOffset = post.get("timezoneOffset", 0);

        // create tweet timeline
        final Timeline tl = new Timeline();
        Map<String, List<Map.Entry<String, Long>>> aggregations = null;
        long hits = 0;
        if (query.length() > 0) {
            if ("all".equals(source)) {
                // start all targets for search concurrently
                final String queryf = query;
                final int timezoneOffsetf = timezoneOffset;
                Thread scraperThread = new Thread() {
                    public void run() {
                        tl.putAll(DAO.scrapeTwitter(queryf, timezoneOffsetf, true)[1]);
                    }
                };
                scraperThread.start();
                Thread backendThread = new Thread() {
                    public void run() {
                        tl.putAll(DAO.searchBackend(queryf, 100, timezoneOffsetf, "cache"));
                    }
                };
                backendThread.start();
                DAO.SearchLocalMessages localSearchResult = new DAO.SearchLocalMessages(query, timezoneOffset, count, 0);
                hits = localSearchResult.hits;
                tl.putAll(localSearchResult.timeline);
                try {backendThread.join(5000);} catch (InterruptedException e) {}
                try {scraperThread.join(8000);} catch (InterruptedException e) {}
            } else {
                if ("twitter".equals(source)) {
                    tl.putAll(DAO.scrapeTwitter(query, timezoneOffset, true)[1]);
                }
    
                // replace the timeline with one from the own index which now includes the remote result
                if ("backend".equals(source)) {
                    tl.putAll(DAO.searchBackend(query, count, timezoneOffset, "cache"));
                }
    
                // replace the timeline with one from the own index which now includes the remote result
                if ("cache".equals(source)) {
                    DAO.SearchLocalMessages localSearchResult = new DAO.SearchLocalMessages(query, timezoneOffset, count, limit, fields);
                    hits = localSearchResult.hits;
                    tl.putAll(localSearchResult.timeline);
                    aggregations = localSearchResult.aggregations;
                }
            }
        }
        hits = Math.max(hits, tl.size());

        if (post.isDoS_servicereduction() && !RemoteAccess.isSleepingForClient(post.getClientHost())) {
            RemoteAccess.sleep(post.getClientHost(), 2000);
        }
        post.setResponse(response, jsonExt ? (jsonp ? "application/javascript": "application/json") : "application/rss+xml;charset=utf-8");
        
        // create json or xml according to path extension
        if (jsonExt) {
            // generate json
            XContentBuilder json = XContentFactory.jsonBuilder();
            if (!minified) json = json.prettyPrint();
            json = json.lfAtEnd();
            json.startObject();
            if (!minified) {
                json.field("readme_0", "THIS JSON IS THE RESULT OF YOUR SEARCH QUERY - THERE IS NO WEB PAGE WHICH SHOWS THE RESULT!");
                json.field("readme_1", "loklak.org is the framework for a message search system, not the portal, read: http://loklak.org/about.html#notasearchportal");
                json.field("readme_2", "This is supposed to be the back-end of a search portal. For the api, see http://loklak.org/api.html");
                json.field("readme_3", "Parameters q=(query), source=(cache|backend|twitter|all), callback=p for jsonp, maximumRecords=(message count), minified=(true|false)");
            }
            json.field("search_metadata").startObject();
            json.field("startIndex", "0");
            json.field("itemsPerPage", Integer.toString(count));
            json.field("count", Integer.toString(tl.size()));
            json.field("hits", hits);
            json.field("period", tl.period());
            json.field("query", query);
            json.field("client", post.getClientHost());
            json.field("servicereduction", post.isDoS_servicereduction() ? "true" : "false");
            json.endObject(); // of search_metadata
            json.field("statuses").startArray();
            for (MessageEntry t: tl) {
                UserEntry u = tl.getUser(t);
                t.toJSON(json, u, true);
            }
            json.endArray();
            
            // aggregations
            json.field("aggregations");
            json.startObject();
            if (aggregations != null) {
                for (Map.Entry<String, List<Map.Entry<String, Long>>> aggregation: aggregations.entrySet()) {
                    json.field(aggregation.getKey());
                    json.startObject();
                    for (Map.Entry<String, Long> a: aggregation.getValue()) {
                        json.field(a.getKey(), a.getValue());
                    }
                    json.endObject(); // of aggregation field
                }
            }
            json.endObject(); // of aggregations
            json.endObject(); // of root

            // write json
            ServletOutputStream sos = response.getOutputStream();
            if (jsonp) sos.print(callback + "(");
            sos.print(json.string());
            if (jsonp) sos.println(");");
            sos.println();
        } else {
            // generate xml
            RSSMessage channel = new RSSMessage();
            channel.setPubDate(new Date());
            channel.setTitle("RSS feed for Twitter search for " + query);
            channel.setDescription("");
            channel.setLink("");
            RSSFeed feed = new RSSFeed(tl.size());
            feed.setChannel(channel);
            for (MessageEntry t: tl) {
                UserEntry u = tl.getUser(t);
                RSSMessage m = new RSSMessage();
                m.setLink(t.getStatusIdUrl().toExternalForm());
                m.setAuthor(u.getName() + " @" + u.getScreenName());
                m.setTitle(u.getName() + " @" + u.getScreenName());
                m.setDescription(t.getText());
                m.setPubDate(t.getCreatedAtDate());
                m.setGuid(t.getIdStr());
                feed.addMessage(m);
            }
            String rss = feed.toString();
            //System.out.println("feed has " + feed.size() + " entries");
            
            // write xml
            response.getOutputStream().write(rss.getBytes("UTF-8"));
        }
        DAO.log(request.getServletPath() + "?" + request.getQueryString());
        } catch (Throwable e) {
            Log.getLog().warn(e.getMessage(), e);
            //e.printStackTrace();
        }
    }
}
