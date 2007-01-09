/**
 * $RCSfile: ConnectionManagerImpl.java,v $
 * $Revision: 3159 $
 * $Date: 2005-12-04 22:56:40 -0300 (Sun, 04 Dec 2005) $
 *
 * Copyright (C) 2007 Jive Software. All rights reserved.
 *
 * This software is published under the terms of the GNU Public License (GPL),
 * a copy of which is included in this distribution.
 */

package org.jivesoftware.wildfire.spi;

import org.apache.mina.common.ExecutorThreadModel;
import org.apache.mina.filter.SSLFilter;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.transport.socket.nio.SocketAcceptor;
import org.apache.mina.transport.socket.nio.SocketAcceptorConfig;
import org.apache.mina.transport.socket.nio.SocketSessionConfig;
import org.jivesoftware.util.*;
import org.jivesoftware.wildfire.*;
import org.jivesoftware.wildfire.container.BasicModule;
import org.jivesoftware.wildfire.http.HttpBindManager;
import org.jivesoftware.wildfire.net.*;
import org.jivesoftware.wildfire.nio.ClientConnectionHandler;
import org.jivesoftware.wildfire.nio.MultiplexerConnectionHandler;
import org.jivesoftware.wildfire.nio.XMPPCodecFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ConnectionManagerImpl extends BasicModule implements ConnectionManager, CertificateEventListener {

    private SocketAcceptor socketAcceptor;
    private SocketAcceptor sslSocketAcceptor;
    private SocketAcceptThread componentSocketThread;
    private SocketAcceptThread serverSocketThread;
    private SocketAcceptor multiplexerSocketAcceptor;
    private ArrayList<ServerPort> ports;

    private SessionManager sessionManager;
    private PacketDeliverer deliverer;
    private PacketRouter router;
    private RoutingTable routingTable;
    private String serverName;
    private XMPPServer server;
    private String localIPAddress = null;

    // Used to know if the sockets can be started (the connection manager has been started)
    private boolean isStarted = false;
    // Used to know if the sockets have been started
    private boolean isSocketStarted = false;

    public ConnectionManagerImpl() {
        super("Connection Manager");
        ports = new ArrayList<ServerPort>(4);
    }

    private void createSocket() {
        if (!isStarted || isSocketStarted || sessionManager == null || deliverer == null ||
                router == null ||
                serverName == null)
        {
            return;
        }
        isSocketStarted = true;

        // Setup port info
        try {
            localIPAddress = InetAddress.getLocalHost().getHostAddress();
        }
        catch (UnknownHostException e) {
            if (localIPAddress == null) {
                localIPAddress = "Unknown";
            }
        }
        // Start the port listener for s2s communication
        startServerListener(localIPAddress);
        // Start the port listener for Connections Multiplexers
        startConnectionManagerListener(localIPAddress);
        // Start the port listener for external components
        startComponentListener(localIPAddress);
        // Start the port listener for clients
        startClientListeners(localIPAddress);
        // Start the port listener for secured clients
        startClientSSLListeners(localIPAddress);
        // Start the HTTP client listener
        startHTTPBindListeners();
    }

    private void startServerListener(String localIPAddress) {
        // Start servers socket unless it's been disabled.
        if (isServerListenerEnabled()) {
            int port = getServerListenerPort();
            try {
                serverSocketThread = new SocketAcceptThread(this, new ServerPort(port, serverName,
                        localIPAddress, false, null, ServerPort.Type.server));
                ports.add(serverSocketThread.getServerPort());
                serverSocketThread.setDaemon(true);
                serverSocketThread.setPriority(Thread.MAX_PRIORITY);
                serverSocketThread.start();

                List<String> params = new ArrayList<String>();
                params.add(Integer.toString(serverSocketThread.getPort()));
                Log.info(LocaleUtils.getLocalizedString("startup.server", params));
            }
            catch (Exception e) {
                System.err.println("Error starting server listener on port " + port + ": " +
                        e.getMessage());
                Log.error(LocaleUtils.getLocalizedString("admin.error.socket-setup"), e);
            }
        }
    }

    private void stopServerListener() {
        if (serverSocketThread != null) {
            serverSocketThread.shutdown();
            ports.remove(serverSocketThread.getServerPort());
            serverSocketThread = null;
        }
    }

    private void startConnectionManagerListener(String localIPAddress) {
        // Start multiplexers socket unless it's been disabled.
        if (isConnectionManagerListenerEnabled()) {
            int port = getConnectionManagerListenerPort();

            // Create SocketAcceptor with correct number of processors
            multiplexerSocketAcceptor = buildSocketAcceptor();
            // Customize Executor that will be used by processors to process incoming stanzas
            ExecutorThreadModel threadModel = ExecutorThreadModel.getInstance("connectionManager");
            int eventThreads = JiveGlobals.getIntProperty("xmpp.multiplex.processing.threads", 16);
            ThreadPoolExecutor eventExecutor = (ThreadPoolExecutor) threadModel.getExecutor();
            eventExecutor.setCorePoolSize(eventThreads + 1);
            eventExecutor.setMaximumPoolSize(eventThreads + 1);
            eventExecutor.setKeepAliveTime(60, TimeUnit.SECONDS);
            multiplexerSocketAcceptor.getDefaultConfig().setThreadModel(threadModel);
            // Add the XMPP codec filter
            multiplexerSocketAcceptor.getFilterChain().addFirst("xmpp", new ProtocolCodecFilter(new XMPPCodecFactory()));

            try {
                // Listen on a specific network interface if it has been set.
                String interfaceName = JiveGlobals.getXMLProperty("xmpp.socket.network.interface");
                InetAddress bindInterface = null;
                if (interfaceName != null) {
                    if (interfaceName.trim().length() > 0) {
                        bindInterface = InetAddress.getByName(interfaceName);
                    }
                }
                // Start accepting connections
                multiplexerSocketAcceptor.bind(new InetSocketAddress(bindInterface, port), new MultiplexerConnectionHandler(serverName));

                ports.add(new ServerPort(port, serverName, localIPAddress, false, null, ServerPort.Type.connectionManager));

                List<String> params = new ArrayList<String>();
                params.add(Integer.toString(port));
                Log.info(LocaleUtils.getLocalizedString("startup.multiplexer", params));
            }
            catch (Exception e) {
                System.err.println("Error starting multiplexer listener on port " + port + ": " +
                        e.getMessage());
                Log.error(LocaleUtils.getLocalizedString("admin.error.socket-setup"), e);
            }
        }
    }

    private void stopConnectionManagerListener() {
        if (multiplexerSocketAcceptor != null) {
            multiplexerSocketAcceptor.unbindAll();
            for (ServerPort port : ports) {
                if (port.isConnectionManagerPort()) {
                    ports.remove(port);
                    break;
                }
            }
            multiplexerSocketAcceptor = null;
        }
    }

    private void startComponentListener(String localIPAddress) {
        // Start components socket unless it's been disabled.
        if (isComponentListenerEnabled()) {
            int port = getComponentListenerPort();
            try {
                componentSocketThread = new SocketAcceptThread(this, new ServerPort(port,
                        serverName, localIPAddress, false, null, ServerPort.Type.component));
                ports.add(componentSocketThread.getServerPort());
                componentSocketThread.setDaemon(true);
                componentSocketThread.setPriority(Thread.MAX_PRIORITY);
                componentSocketThread.start();

                List<String> params = new ArrayList<String>();
                params.add(Integer.toString(componentSocketThread.getPort()));
                Log.info(LocaleUtils.getLocalizedString("startup.component", params));
            }
            catch (Exception e) {
                System.err.println("Error starting component listener on port " + port + ": " +
                        e.getMessage());
                Log.error(LocaleUtils.getLocalizedString("admin.error.socket-setup"), e);
            }
        }
    }

    private void stopComponentListener() {
        if (componentSocketThread != null) {
            componentSocketThread.shutdown();
            ports.remove(componentSocketThread.getServerPort());
            componentSocketThread = null;
        }
    }

    private void startClientListeners(String localIPAddress) {
        // Start clients plain socket unless it's been disabled.
        if (isClientListenerEnabled()) {
            int port = getClientListenerPort();
            // Create SocketAcceptor with correct number of processors
            socketAcceptor = buildSocketAcceptor();
            // Customize Executor that will be used by processors to process incoming stanzas
            ExecutorThreadModel threadModel = ExecutorThreadModel.getInstance("client");
            int eventThreads = JiveGlobals.getIntProperty("xmpp.client.processing.threads", 16);
            ThreadPoolExecutor eventExecutor = (ThreadPoolExecutor)threadModel.getExecutor();
            eventExecutor.setCorePoolSize(eventThreads + 1);
            eventExecutor.setMaximumPoolSize(eventThreads + 1);
            eventExecutor.setKeepAliveTime(60, TimeUnit.SECONDS);

            socketAcceptor.getDefaultConfig().setThreadModel(threadModel);
            // Add the XMPP codec filter
            socketAcceptor.getFilterChain().addFirst("xmpp", new ProtocolCodecFilter(new XMPPCodecFactory()));

            try {
                // Listen on a specific network interface if it has been set.
                String interfaceName = JiveGlobals.getXMLProperty("xmpp.socket.network.interface");
                InetAddress bindInterface = null;
                if (interfaceName != null) {
                    if (interfaceName.trim().length() > 0) {
                        bindInterface = InetAddress.getByName(interfaceName);
                    }
                }
                // Start accepting connections
                socketAcceptor
                        .bind(new InetSocketAddress(bindInterface, port), new ClientConnectionHandler(serverName));

                ports.add(new ServerPort(port, serverName, localIPAddress, false, null, ServerPort.Type.client));

                List<String> params = new ArrayList<String>();
                params.add(Integer.toString(port));
                Log.info(LocaleUtils.getLocalizedString("startup.plain", params));
            }
            catch (Exception e) {
                System.err.println("Error starting XMPP listener on port " + port + ": " +
                        e.getMessage());
                Log.error(LocaleUtils.getLocalizedString("admin.error.socket-setup"), e);
            }
        }
    }

    private void stopClientListeners() {
        if (socketAcceptor != null) {
            socketAcceptor.unbindAll();
            for (ServerPort port : ports) {
                if (port.isClientPort() && !port.isSecure()) {
                    ports.remove(port);
                    break;
                }
            }
            socketAcceptor = null;
        }
    }

    private void startClientSSLListeners(String localIPAddress) {
        // Start clients SSL unless it's been disabled.
        if (isClientSSLListenerEnabled()) {
            int port = getClientSSLListenerPort();
            String algorithm = JiveGlobals.getProperty("xmpp.socket.ssl.algorithm");
            if ("".equals(algorithm) || algorithm == null) {
                algorithm = "TLS";
            }
            try {
                // Create SocketAcceptor with correct number of processors
                sslSocketAcceptor = buildSocketAcceptor();
                // Customize Executor that will be used by processors to process incoming stanzas
                ExecutorThreadModel threadModel = ExecutorThreadModel.getInstance("client_ssl");
                int eventThreads = JiveGlobals.getIntProperty("xmpp.client_ssl.processing.threads", 16);
                ThreadPoolExecutor eventExecutor = (ThreadPoolExecutor)threadModel.getExecutor();
                eventExecutor.setCorePoolSize(eventThreads + 1);
                eventExecutor.setMaximumPoolSize(eventThreads + 1);
                eventExecutor.setKeepAliveTime(60, TimeUnit.SECONDS);

                sslSocketAcceptor.getDefaultConfig().setThreadModel(threadModel);
                // Add the XMPP codec filter
                sslSocketAcceptor.getFilterChain().addFirst("xmpp", new ProtocolCodecFilter(new XMPPCodecFactory()));

                // Add the SSL filter now since sockets are "borned" encrypted in the old ssl method
                SSLContext sslContext = SSLContext.getInstance(algorithm);
                KeyManagerFactory keyFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyFactory.init(SSLConfig.getKeyStore(), SSLConfig.getKeyPassword().toCharArray());
                TrustManagerFactory trustFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustFactory.init(SSLConfig.getTrustStore());

                sslContext.init(keyFactory.getKeyManagers(),
                        trustFactory.getTrustManagers(),
                        new java.security.SecureRandom());

                sslSocketAcceptor.getFilterChain().addFirst("tls", new SSLFilter(sslContext));

                // Listen on a specific network interface if it has been set.
                String interfaceName = JiveGlobals.getXMLProperty("xmpp.socket.network.interface");
                InetAddress bindInterface = null;
                if (interfaceName != null) {
                    if (interfaceName.trim().length() > 0) {
                        bindInterface = InetAddress.getByName(interfaceName);
                    }
                }
                // Start accepting connections
                sslSocketAcceptor
                        .bind(new InetSocketAddress(bindInterface, port), new ClientConnectionHandler(serverName));

                ports.add(new ServerPort(port, serverName, localIPAddress, true, null, ServerPort.Type.client));

                List<String> params = new ArrayList<String>();
                params.add(Integer.toString(port));
                Log.info(LocaleUtils.getLocalizedString("startup.ssl", params));
            }
            catch (Exception e) {
                System.err.println("Error starting SSL XMPP listener on port " + port + ": " +
                        e.getMessage());
                Log.error(LocaleUtils.getLocalizedString("admin.error.ssl"), e);
            }
        }
    }

    private void stopClientSSLListeners() {
        if (sslSocketAcceptor != null) {
            sslSocketAcceptor.unbindAll();
            for (ServerPort port : ports) {
                if (port.isClientPort() && port.isSecure()) {
                    ports.remove(port);
                    break;
                }
            }
            sslSocketAcceptor = null;
        }
    }

    private void restartClientSSLListeners() {
        if (!isSocketStarted) {
            return;
        }
        // Setup port info
        try {
            localIPAddress = InetAddress.getLocalHost().getHostAddress();
        }
        catch (UnknownHostException e) {
            if (localIPAddress == null) {
                localIPAddress = "Unknown";
            }
        }
        stopClientSSLListeners();
        startClientSSLListeners(localIPAddress);
    }

    public Iterator<ServerPort> getPorts() {
        return ports.iterator();
    }

    public SocketReader createSocketReader(Socket sock, boolean isSecure, ServerPort serverPort,
            boolean useBlockingMode) throws IOException {
        if (serverPort.isComponentPort()) {
            SocketConnection conn = new SocketConnection(deliverer, sock, isSecure);
            return new ComponentSocketReader(router, routingTable, serverName, sock, conn,
                    useBlockingMode);
        }
        else if (serverPort.isServerPort()) {
            SocketConnection conn = new SocketConnection(deliverer, sock, isSecure);
            return new ServerSocketReader(router, routingTable, serverName, sock, conn,
                    useBlockingMode);
        }
        return null;
    }

    private void startHTTPBindListeners() {
        HttpBindManager.getInstance().start();
    }

    public void initialize(XMPPServer server) {
        super.initialize(server);
        this.server = server;
        router = server.getPacketRouter();
        routingTable = server.getRoutingTable();
        deliverer = server.getPacketDeliverer();
        sessionManager = server.getSessionManager();
    }

    public void enableClientListener(boolean enabled) {
        if (enabled == isClientListenerEnabled()) {
            // Ignore new setting
            return;
        }
        if (enabled) {
            JiveGlobals.setProperty("xmpp.socket.plain.active", "true");
            // Start the port listener for clients
            startClientListeners(localIPAddress);
        }
        else {
            JiveGlobals.setProperty("xmpp.socket.plain.active", "false");
            // Stop the port listener for clients
            stopClientListeners();
        }
    }

    public boolean isClientListenerEnabled() {
        return JiveGlobals.getBooleanProperty("xmpp.socket.plain.active", true);
    }

    public void enableClientSSLListener(boolean enabled) {
        if (enabled == isClientSSLListenerEnabled()) {
            // Ignore new setting
            return;
        }
        if (enabled) {
            JiveGlobals.setProperty("xmpp.socket.ssl.active", "true");
            // Start the port listener for secured clients
            startClientSSLListeners(localIPAddress);
        }
        else {
            JiveGlobals.setProperty("xmpp.socket.ssl.active", "false");
            // Stop the port listener for secured clients
            stopClientSSLListeners();
        }
    }

    public boolean isClientSSLListenerEnabled() {
        try {
            return JiveGlobals.getBooleanProperty("xmpp.socket.ssl.active", true) && SSLConfig.getKeyStore().size() > 0;
        } catch (KeyStoreException e) {
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    public void enableComponentListener(boolean enabled) {
        if (enabled == isComponentListenerEnabled()) {
            // Ignore new setting
            return;
        }
        if (enabled) {
            JiveGlobals.setProperty("xmpp.component.socket.active", "true");
            // Start the port listener for external components
            startComponentListener(localIPAddress);
        }
        else {
            JiveGlobals.setProperty("xmpp.component.socket.active", "false");
            // Stop the port listener for external components
            stopComponentListener();
        }
    }

    public boolean isComponentListenerEnabled() {
        return JiveGlobals.getBooleanProperty("xmpp.component.socket.active", false);
    }

    public void enableServerListener(boolean enabled) {
        if (enabled == isServerListenerEnabled()) {
            // Ignore new setting
            return;
        }
        if (enabled) {
            JiveGlobals.setProperty("xmpp.server.socket.active", "true");
            // Start the port listener for s2s communication
            startServerListener(localIPAddress);
        }
        else {
            JiveGlobals.setProperty("xmpp.server.socket.active", "false");
            // Stop the port listener for s2s communication
            stopServerListener();
        }
    }

    public boolean isServerListenerEnabled() {
        return JiveGlobals.getBooleanProperty("xmpp.server.socket.active", true);
    }

    public void enableConnectionManagerListener(boolean enabled) {
        if (enabled == isConnectionManagerListenerEnabled()) {
            // Ignore new setting
            return;
        }
        if (enabled) {
            JiveGlobals.setProperty("xmpp.multiplex.socket.active", "true");
            // Start the port listener for s2s communication
            startConnectionManagerListener(localIPAddress);
        }
        else {
            JiveGlobals.setProperty("xmpp.multiplex.socket.active", "false");
            // Stop the port listener for s2s communication
            stopConnectionManagerListener();
        }
    }

    public boolean isConnectionManagerListenerEnabled() {
        return JiveGlobals.getBooleanProperty("xmpp.multiplex.socket.active", false);
    }

    public void setClientListenerPort(int port) {
        if (port == getClientListenerPort()) {
            // Ignore new setting
            return;
        }
        JiveGlobals.setProperty("xmpp.socket.plain.port", String.valueOf(port));
        // Stop the port listener for clients
        stopClientListeners();
        if (isClientListenerEnabled()) {
            // Start the port listener for clients
            startClientListeners(localIPAddress);
        }
    }

    public int getClientListenerPort() {
        return JiveGlobals.getIntProperty("xmpp.socket.plain.port", DEFAULT_PORT);
    }

    public void setClientSSLListenerPort(int port) {
        if (port == getClientSSLListenerPort()) {
            // Ignore new setting
            return;
        }
        JiveGlobals.setProperty("xmpp.socket.ssl.port", String.valueOf(port));
        // Stop the port listener for secured clients
        stopClientSSLListeners();
        if (isClientSSLListenerEnabled()) {
            // Start the port listener for secured clients
            startClientSSLListeners(localIPAddress);
        }
    }

    public int getClientSSLListenerPort() {
        return JiveGlobals.getIntProperty("xmpp.socket.ssl.port", DEFAULT_SSL_PORT);
    }

    public void setComponentListenerPort(int port) {
        if (port == getComponentListenerPort()) {
            // Ignore new setting
            return;
        }
        JiveGlobals.setProperty("xmpp.component.socket.port", String.valueOf(port));
        // Stop the port listener for external components
        stopComponentListener();
        if (isComponentListenerEnabled()) {
            // Start the port listener for external components
            startComponentListener(localIPAddress);
        }
    }

    public int getComponentListenerPort() {
        return JiveGlobals.getIntProperty("xmpp.component.socket.port", DEFAULT_COMPONENT_PORT);
    }

    public void setServerListenerPort(int port) {
        if (port == getServerListenerPort()) {
            // Ignore new setting
            return;
        }
        JiveGlobals.setProperty("xmpp.server.socket.port", String.valueOf(port));
        // Stop the port listener for s2s communication
        stopServerListener();
        if (isServerListenerEnabled()) {
            // Start the port listener for s2s communication
            startServerListener(localIPAddress);
        }
    }

    public int getServerListenerPort() {
        return JiveGlobals.getIntProperty("xmpp.server.socket.port", DEFAULT_SERVER_PORT);
    }

    public void setConnectionManagerListenerPort(int port) {
        if (port == getConnectionManagerListenerPort()) {
            // Ignore new setting
            return;
        }
        JiveGlobals.setProperty("xmpp.multiplex.socket.port", String.valueOf(port));
        // Stop the port listener for connection managers
        stopConnectionManagerListener();
        if (isConnectionManagerListenerEnabled()) {
            // Start the port listener for connection managers
            startConnectionManagerListener(localIPAddress);
        }
    }

    public int getConnectionManagerListenerPort() {
        return JiveGlobals.getIntProperty("xmpp.multiplex.socket.port", DEFAULT_MULTIPLEX_PORT);
    }

    // #####################################################################
    // Certificates events
    // #####################################################################

    public void certificateCreated(KeyStore keyStore, String alias, X509Certificate cert) {
        restartClientSSLListeners();
    }

    public void certificateDeleted(KeyStore keyStore, String alias) {
        restartClientSSLListeners();
    }

    public void certificateSigned(KeyStore keyStore, String alias, List<X509Certificate> certificates) {
        restartClientSSLListeners();
    }

    private SocketAcceptor buildSocketAcceptor() {
        SocketAcceptor socketAcceptor;
        // Create SocketAcceptor with correct number of processors
        int ioThreads = JiveGlobals.getIntProperty("xmpp.processor.count", Runtime.getRuntime().availableProcessors());
        // Set the executor that processors will use. Note that processors will use another executor
        // for processing events (i.e. incoming traffic)
        Executor ioExecutor = new ThreadPoolExecutor(
            ioThreads + 1, ioThreads + 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>() );
        socketAcceptor = new SocketAcceptor(ioThreads, ioExecutor);
        // Set that it will be possible to bind a socket if there is a connection in the timeout state
        SocketAcceptorConfig socketAcceptorConfig = (SocketAcceptorConfig) socketAcceptor.getDefaultConfig();
        socketAcceptorConfig.setReuseAddress(true);
        // Set the listen backlog (queue) length. Default is 50.
        socketAcceptorConfig.setBacklog(JiveGlobals.getIntProperty("xmpp.socket.backlog", 50));

        // Set default (low level) settings for new socket connections
        SocketSessionConfig socketSessionConfig = socketAcceptorConfig.getSessionConfig();
        //socketSessionConfig.setKeepAlive();
        int receiveBuffer = JiveGlobals.getIntProperty("xmpp.socket.buffer.receive", -1);
        if (receiveBuffer > 0 ) {
            socketSessionConfig.setReceiveBufferSize(receiveBuffer);
        }
        int sendBuffer = JiveGlobals.getIntProperty("xmpp.socket.buffer.send", -1);
        if (sendBuffer > 0 ) {
            socketSessionConfig.setSendBufferSize(sendBuffer);
        }
        int linger = JiveGlobals.getIntProperty("xmpp.socket.linger", -1);
        if (linger > 0 ) {
            socketSessionConfig.setSoLinger(linger);
        }
        socketSessionConfig.setTcpNoDelay(
                JiveGlobals.getBooleanProperty("xmpp.socket.tcp-nodelay", socketSessionConfig.isTcpNoDelay()));
        return socketAcceptor;
    }

    // #####################################################################
    // Module management
    // #####################################################################

    public void start() {
        super.start();
        isStarted = true;
        serverName = server.getServerInfo().getName();
        createSocket();
        SocketSendingTracker.getInstance().start();
        CertificateManager.addListener(this);
    }

    public void stop() {
        super.stop();
        stopClientListeners();
        stopClientSSLListeners();
        stopComponentListener();
        stopConnectionManagerListener();
        stopServerListener();
        HttpBindManager.getInstance().stop();
        SocketSendingTracker.getInstance().shutdown();
        CertificateManager.removeListener(this);
        serverName = null;
    }
}