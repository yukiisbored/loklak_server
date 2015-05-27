/**
 *  SuggestServlet
 *  Copyright 29.04.2015 by Michael Peter Christen, @0rb1t3r
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
import java.io.StringWriter;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.loklak.data.DAO;
import org.loklak.data.UserEntry;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.MinimalPrettyPrinter;

public class UserServlet extends HttpServlet {
   
    private static final long serialVersionUID = 8578478303032749879L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGet(request, response);
    }
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        RemoteAccess.Post post = RemoteAccess.evaluate(request);
     
        // manage DoS
        if (post.isDoS_blackout()) {response.sendError(503, "your request frequency is too high"); return;}
        
        String callback = post.get("callback", "");
        boolean jsonp = callback != null && callback.length() > 0;
        boolean local = post.isLocalhostAccess();
        boolean minified = post.get("minified", false);
        //int timezoneOffset = post.get("timezoneOffset", 0);
        //boolean account = post.get("account", false);
        boolean update = local && "update".equals(post.get("action", ""));
        String screen_name = post.get("screen_name", "");
        //String provider = post.get("provider", "");
        //String consumerKey = post.get("consumerKey", "");
        //String consumerSecret = post.get("consumerSecret", "");
        UserEntry userEntry = DAO.SearchLocalUser(screen_name);
        
        post.setResponse(response, "application/javascript");
        
        // generate json
        final StringWriter s = new StringWriter();
        JsonGenerator json = DAO.jsonFactory.createGenerator(s);
        json.setPrettyPrinter(minified ? new MinimalPrettyPrinter() : new DefaultPrettyPrinter());

        json.writeStartObject();

        json.writeObjectFieldStart("search_metadata");
        json.writeObjectField("count", userEntry == null ? "0" : "1");
        json.writeObjectField("client", post.getClientHost());
        json.writeEndObject(); // of search_metadata

        json.writeArrayFieldStart("users");
        if (userEntry != null) {
            userEntry.toJSON(json);
        }
        json.writeEndArray(); // of users
        json.writeEndObject(); // of root
        json.close();
        
        // write json
        ServletOutputStream sos = response.getOutputStream();
        if (jsonp) sos.print(callback + "(");
        sos.print(s.toString());
        if (jsonp) sos.println(");");
        sos.println();
    }
    
}
