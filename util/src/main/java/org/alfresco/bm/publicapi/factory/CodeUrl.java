/*
 * Copyright 2012 Alfresco Software Limited.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * This file is part of an unsupported extension to Alfresco.
 */
package org.alfresco.bm.publicapi.factory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;

/**
 * 
 * @author jottley
 * 
 */
public class CodeUrl
{

    public static final String CODE  = "code";
    public static final String SCOPE = "scope";
    public static final String STATE = "state";


    private URL                url;


    public CodeUrl(String authUrl)
        throws MalformedURLException
    {
        this.url = new URL(authUrl);
    }


    public String getQuery()
    {
        return url.getQuery();
    }


    private String[] parseQuery()
    {
        return this.getQuery().split("&");
    }


    public HashMap<String, String> getQueryMap()
    {
        HashMap<String, String> queryMap = new HashMap<String, String>();

        String[] query = this.parseQuery();
        for (int i = 0; i < query.length; i++)
        {
            String[] kv = query[i].split("=");

            queryMap.put(kv[0], kv[1]);
        }

        return queryMap;
    }


    @Override
    public String toString()
    {
        return url.toString();
    }
}
