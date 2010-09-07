// ========================================================================
// Copyright 2007 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//========================================================================

package org.cometd.server;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import javax.servlet.GenericServlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Transport;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.server.transport.HttpTransport;

/**
 * The cometd Servlet.
 * </p>
 * <p>
 * The cometd Servlet maps HTTP requests to the {@link HttpTransport} of a {@link BayeuxServerImpl} instance.
 * If a {@link BayeuxServerImpl} instance is discovered in the {@link BayeuxServer#ATTRIBUTE} servlet context
 * attribute, then it is used, otherwise a new instance is created and the {@link #initializeBayeux(BayeuxServerImpl)}
 * method called.
 * </p>
 * <p>
 *
 * </p>
 */
public class CometdServlet extends GenericServlet
{
    private static final long serialVersionUID = 3637310585741732936L;
    public static final int CONFIG_LEVEL=1;
    public static final int INFO_LEVEL=2;
    public static final int DEBUG_LEVEL=3;

    private BayeuxServerImpl _bayeux;
    private final ThreadLocal<HttpServletRequest> _currentRequest = new ThreadLocal<HttpServletRequest>();
    private int _logLevel;
    private HttpTransport[] _transports;


    public BayeuxServerImpl getBayeux()
    {
        if (_bayeux==null)
        {
            _bayeux= new BayeuxServerImpl();
            initializeBayeux(_bayeux);
        }
        return _bayeux;
    }

    /* ------------------------------------------------------------ */
    /** Initialise the BayeuxServer.
     * Called by {@link #init()} if a bayeux server is constructed by
     * this servlet. The default implementation
     * calls {@link BayeuxServerImpl#initializeDefaultTransports()}.
     *
     * @param bayeux
     */
    protected void initializeBayeux(BayeuxServerImpl bayeux)
    {
        bayeux.initializeDefaultTransports();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void init() throws ServletException
    {

        _bayeux=(BayeuxServerImpl)getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
        if (_bayeux==null)
            getServletContext().setAttribute(BayeuxServer.ATTRIBUTE,getBayeux());

        if (getInitParameter("logLevel")!=null)
        {
            _logLevel=Integer.parseInt(getInitParameter("logLevel"));
            if (_logLevel>=DEBUG_LEVEL)
                _bayeux.getLogger().setDebugEnabled(true);
        }

        // Get any specific options as init parameter
        HashSet<String> qualified_names = new HashSet<String>();
        for (String name :_bayeux.getKnownTransportNames())
        {
            Transport transport = _bayeux.getTransport(name);
            {
                for (String option : transport.getOptionNames())
                {
                    qualified_names.add(option);
                    String prefix=transport.getOptionPrefix();
                    while (prefix!=null)
                    {
                        qualified_names.add(prefix+"."+option);
                        int dot=prefix.lastIndexOf('.');
                        prefix=dot<0?null:prefix.substring(0,dot);
                    }
                }
            }
        }

        for (String option : qualified_names)
        {
            Object value = getInitParameter(option);
            if (value!=null)
                _bayeux.setOption(option,value);
        }

        for (String name :_bayeux.getKnownTransportNames())
        {
            Transport transport = _bayeux.getTransport(name);
            if (transport instanceof AbstractServerTransport)
                ((AbstractServerTransport)transport).init();
        }

        if (_logLevel>=CONFIG_LEVEL)
        {
            for (Map.Entry<String, Object> entry : _bayeux.getOptions().entrySet())
            {
                _bayeux.getLogger().info(entry.getKey()+"="+entry.getValue());
            }
        }

        _transports=new HttpTransport[_bayeux.getAllowedTransports().size()];
        int i=0;
        for (String t : _bayeux.getAllowedTransports())
        {
            Transport transport = _bayeux.getTransport(t);
            _transports[i++]=transport instanceof HttpTransport?(HttpTransport)transport:null;
        }

        try
        {
            _bayeux.start();
        }
        catch(Exception e)
        {
            throw new ServletException(e);
        }
    }

    @Override
    public void service(ServletRequest req, ServletResponse resp) throws ServletException, IOException
    {
        HttpServletRequest request=(HttpServletRequest)req;
        HttpServletResponse response=(HttpServletResponse)resp;

        _currentRequest.set(request);
        try
        {
            service(request,response);
        }
        finally
        {
            _currentRequest.set(null);
        }
    }

    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        if ("OPTIONS".equals(request.getMethod()))
        {
            serviceOptions(request, response);
            return;
        }

        HttpTransport transport=null;
        for (HttpTransport t : _transports)
        {
            if (t!=null && t.accept(request))
            {
                transport=t;
                break;
            }
        }

        if (transport==null)
        {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unknown Bayeux Transport");
        }
        else
        {
            try
            {
                _bayeux.setCurrentTransport(transport);
                transport.setCurrentRequest(request);
                transport.handle(request,response);
            }
            finally
            {
                _bayeux.setCurrentTransport(null);
                transport.setCurrentRequest(null);
            }
        }
    }

    protected void serviceOptions(HttpServletRequest request, HttpServletResponse response)
    {
        // OPTIONS requests are made by browsers that are CORS compliant
        // (see http://www.w3.org/TR/cors/) during a "preflight request".
        // Preflight requests happen for each different new URL, then results are cached
        // by the browser.
        // For the Bayeux protocol, preflight requests happen for URLs such as
        // "/cometd/handshake", "/cometd/connect", etc, since the Bayeux clients append
        // the Bayeux message type to the base Bayeux server URL.
        // Just return 200 OK, there is nothing more to add to such requests.
    }

    /* ------------------------------------------------------------ */
    /**
     * @see javax.servlet.GenericServlet#destroy()
     */
    @Override
    public void destroy()
    {
        for (ServerSessionImpl session : _bayeux.getSessions())
        {
            session.cancelSchedule();
        }
    }
}