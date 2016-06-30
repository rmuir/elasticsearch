/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.transport.netty;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.Booleans;
import org.elasticsearch.common.SwallowsExceptions;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.bytes.ReleasablePagedBytesReference;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.ReleasableBytesStream;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.network.NetworkService.TcpSettings;
import org.elasticsearch.common.network.NetworkUtils;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.monitor.jvm.JvmInfo;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.BytesTransportRequest;
import org.elasticsearch.transport.ConnectTransportException;
import org.elasticsearch.transport.TcpTransport;
import org.elasticsearch.transport.TransportMessage;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportServiceAdapter;
import org.elasticsearch.transport.TransportSettings;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.AdaptiveReceiveBufferSizePredictorFactory;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.FixedReceiveBufferSizePredictorFactory;
import org.jboss.netty.channel.ReceiveBufferSizePredictorFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioWorkerPool;
import org.jboss.netty.channel.socket.oio.OioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.oio.OioServerSocketChannelFactory;
import org.jboss.netty.util.HashedWheelTimer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import static org.elasticsearch.common.settings.Setting.byteSizeSetting;
import static org.elasticsearch.common.settings.Setting.intSetting;
import static org.elasticsearch.common.util.concurrent.ConcurrentCollections.newConcurrentMap;
import static org.elasticsearch.common.util.concurrent.EsExecutors.daemonThreadFactory;

/**
 * There are 4 types of connections per node, low/med/high/ping. Low if for batch oriented APIs (like recovery or
 * batch) with high payload that will cause regular request. (like search or single index) to take
 * longer. Med is for the typical search / single doc index. And High for things like cluster state. Ping is reserved for
 * sending out ping requests to other nodes.
 */
public class NettyTransport extends TcpTransport<Channel> {

    static {
        NettyUtils.setup();
    }

    public static final Setting<Integer> WORKER_COUNT =
        new Setting<>("transport.netty.worker_count",
            (s) -> Integer.toString(EsExecutors.boundedNumberOfProcessors(s) * 2),
            (s) -> Setting.parseInt(s, 1, "transport.netty.worker_count"), Property.NodeScope);

    public static final Setting<ByteSizeValue> NETTY_MAX_CUMULATION_BUFFER_CAPACITY =
        Setting.byteSizeSetting("transport.netty.max_cumulation_buffer_capacity", new ByteSizeValue(-1), Property.NodeScope);
    public static final Setting<Integer> NETTY_MAX_COMPOSITE_BUFFER_COMPONENTS =
        Setting.intSetting("transport.netty.max_composite_buffer_components", -1, -1, Property.NodeScope);

    // See AdaptiveReceiveBufferSizePredictor#DEFAULT_XXX for default values in netty..., we can use higher ones for us, even fixed one
    public static final Setting<ByteSizeValue> NETTY_RECEIVE_PREDICTOR_SIZE = Setting.byteSizeSetting(
        "transport.netty.receive_predictor_size",
        settings -> {
            long defaultReceiverPredictor = 512 * 1024;
            if (JvmInfo.jvmInfo().getMem().getDirectMemoryMax().bytes() > 0) {
                // we can guess a better default...
                long l = (long) ((0.3 * JvmInfo.jvmInfo().getMem().getDirectMemoryMax().bytes()) / WORKER_COUNT.get(settings));
                defaultReceiverPredictor = Math.min(defaultReceiverPredictor, Math.max(l, 64 * 1024));
            }
            return new ByteSizeValue(defaultReceiverPredictor).toString();
        }, Property.NodeScope);
    public static final Setting<ByteSizeValue> NETTY_RECEIVE_PREDICTOR_MIN =
        byteSizeSetting("transport.netty.receive_predictor_min", NETTY_RECEIVE_PREDICTOR_SIZE, Property.NodeScope);
    public static final Setting<ByteSizeValue> NETTY_RECEIVE_PREDICTOR_MAX =
        byteSizeSetting("transport.netty.receive_predictor_max", NETTY_RECEIVE_PREDICTOR_SIZE, Property.NodeScope);
    public static final Setting<Integer> NETTY_BOSS_COUNT =
        intSetting("transport.netty.boss_count", 1, 1, Property.NodeScope);


    protected final ByteSizeValue maxCumulationBufferCapacity;
    protected final int maxCompositeBufferComponents;
    protected final ReceiveBufferSizePredictorFactory receiveBufferSizePredictorFactory;
    protected final int workerCount;
    protected final ByteSizeValue receivePredictorMin;
    protected final ByteSizeValue receivePredictorMax;
    // package private for testing
    volatile OpenChannelsHandler serverOpenChannels;
    protected volatile ClientBootstrap clientBootstrap;
    protected final Map<String, ServerBootstrap> serverBootstraps = newConcurrentMap();

    @Inject
    public NettyTransport(Settings settings, ThreadPool threadPool, NetworkService networkService, BigArrays bigArrays,
                          NamedWriteableRegistry namedWriteableRegistry, CircuitBreakerService circuitBreakerService) {
        super("netty", settings, threadPool, bigArrays, circuitBreakerService, namedWriteableRegistry, networkService);
        this.workerCount = WORKER_COUNT.get(settings);
        this.maxCumulationBufferCapacity = NETTY_MAX_CUMULATION_BUFFER_CAPACITY.get(settings);
        this.maxCompositeBufferComponents = NETTY_MAX_COMPOSITE_BUFFER_COMPONENTS.get(settings);

        // See AdaptiveReceiveBufferSizePredictor#DEFAULT_XXX for default values in netty..., we can use higher ones for us, even fixed one
        this.receivePredictorMin = NETTY_RECEIVE_PREDICTOR_MIN.get(settings);
        this.receivePredictorMax = NETTY_RECEIVE_PREDICTOR_MAX.get(settings);
        if (receivePredictorMax.bytes() == receivePredictorMin.bytes()) {
            receiveBufferSizePredictorFactory = new FixedReceiveBufferSizePredictorFactory((int) receivePredictorMax.bytes());
        } else {
            receiveBufferSizePredictorFactory = new AdaptiveReceiveBufferSizePredictorFactory((int) receivePredictorMin.bytes(),
                (int) receivePredictorMin.bytes(), (int) receivePredictorMax.bytes());
        }
    }

    TransportServiceAdapter transportServiceAdapter() {
        return transportServiceAdapter;
    }

    @Override
    protected void doStart() {
        boolean success = false;
        try {
            clientBootstrap = createClientBootstrap();
            if (NetworkService.NETWORK_SERVER.get(settings)) {
                final OpenChannelsHandler openChannels = new OpenChannelsHandler(logger);
                this.serverOpenChannels = openChannels;
                // loop through all profiles and start them up, special handling for default one
                for (Map.Entry<String, Settings> entry : buildProfileSettings().entrySet()) {
                    // merge fallback settings with default settings with profile settings so we have complete settings with default values
                    final Settings settings = Settings.builder()
                        .put(createFallbackSettings())
                        .put(entry.getValue()).build();
                    createServerBootstrap(entry.getKey(), settings);
                    bindServer(entry.getKey(), settings);
                }
            }
            super.doStart();
            success = true;
        } finally {
            if (success == false) {
                doStop();
            }
        }
    }

    private ClientBootstrap createClientBootstrap() {
        if (blockingClient) {
            clientBootstrap = new ClientBootstrap(new OioClientSocketChannelFactory(
                Executors.newCachedThreadPool(daemonThreadFactory(settings, TRANSPORT_CLIENT_WORKER_THREAD_NAME_PREFIX))));
        } else {
            int bossCount = NETTY_BOSS_COUNT.get(settings);
            clientBootstrap = new ClientBootstrap(
                new NioClientSocketChannelFactory(
                    Executors.newCachedThreadPool(daemonThreadFactory(settings, TRANSPORT_CLIENT_BOSS_THREAD_NAME_PREFIX)),
                    bossCount,
                    new NioWorkerPool(Executors.newCachedThreadPool(
                        daemonThreadFactory(settings, TRANSPORT_CLIENT_WORKER_THREAD_NAME_PREFIX)), workerCount),
                    new HashedWheelTimer(daemonThreadFactory(settings, "transport_client_timer"))));
        }
        clientBootstrap.setPipelineFactory(configureClientChannelPipelineFactory());
        clientBootstrap.setOption("connectTimeoutMillis", connectTimeout.millis());

        boolean tcpNoDelay = TCP_NO_DELAY.get(settings);
        clientBootstrap.setOption("tcpNoDelay", tcpNoDelay);

        boolean tcpKeepAlive = TCP_KEEP_ALIVE.get(settings);
        clientBootstrap.setOption("keepAlive", tcpKeepAlive);

        ByteSizeValue tcpSendBufferSize = TCP_SEND_BUFFER_SIZE.get(settings);
        if (tcpSendBufferSize.bytes() > 0) {
            clientBootstrap.setOption("sendBufferSize", tcpSendBufferSize.bytes());
        }

        ByteSizeValue tcpReceiveBufferSize = TCP_RECEIVE_BUFFER_SIZE.get(settings);
        if (tcpReceiveBufferSize.bytes() > 0) {
            clientBootstrap.setOption("receiveBufferSize", tcpReceiveBufferSize.bytes());
        }

        clientBootstrap.setOption("receiveBufferSizePredictorFactory", receiveBufferSizePredictorFactory);

        boolean reuseAddress = TCP_REUSE_ADDRESS.get(settings);
        clientBootstrap.setOption("reuseAddress", reuseAddress);

        return clientBootstrap;
    }

    private Settings createFallbackSettings() {
        Settings.Builder fallbackSettingsBuilder = Settings.builder();

        List<String> fallbackBindHost = TransportSettings.BIND_HOST.get(settings);
        if (fallbackBindHost.isEmpty() == false) {
            fallbackSettingsBuilder.putArray("bind_host", fallbackBindHost);
        }

        List<String> fallbackPublishHost = TransportSettings.PUBLISH_HOST.get(settings);
        if (fallbackPublishHost.isEmpty() == false) {
            fallbackSettingsBuilder.putArray("publish_host", fallbackPublishHost);
        }

        boolean fallbackTcpNoDelay = settings.getAsBoolean("transport.netty.tcp_no_delay", TcpSettings.TCP_NO_DELAY.get(settings));
        fallbackSettingsBuilder.put("tcp_no_delay", fallbackTcpNoDelay);

        boolean fallbackTcpKeepAlive = settings.getAsBoolean("transport.netty.tcp_keep_alive", TcpSettings.TCP_KEEP_ALIVE.get(settings));
        fallbackSettingsBuilder.put("tcp_keep_alive", fallbackTcpKeepAlive);

        boolean fallbackReuseAddress = settings.getAsBoolean("transport.netty.reuse_address", TcpSettings.TCP_REUSE_ADDRESS.get(settings));
        fallbackSettingsBuilder.put("reuse_address", fallbackReuseAddress);

        ByteSizeValue fallbackTcpSendBufferSize = settings.getAsBytesSize("transport.netty.tcp_send_buffer_size",
            TCP_SEND_BUFFER_SIZE.get(settings));
        if (fallbackTcpSendBufferSize.bytes() >= 0) {
            fallbackSettingsBuilder.put("tcp_send_buffer_size", fallbackTcpSendBufferSize);
        }

        ByteSizeValue fallbackTcpBufferSize = settings.getAsBytesSize("transport.netty.tcp_receive_buffer_size",
            TCP_RECEIVE_BUFFER_SIZE.get(settings));
        if (fallbackTcpBufferSize.bytes() >= 0) {
            fallbackSettingsBuilder.put("tcp_receive_buffer_size", fallbackTcpBufferSize);
        }

        return fallbackSettingsBuilder.build();
    }

    private void createServerBootstrap(String name, Settings settings) {
        boolean blockingServer = TCP_BLOCKING_SERVER.get(settings);
        String port = settings.get("port");
        String bindHost = settings.get("bind_host");
        String publishHost = settings.get("publish_host");
        String tcpNoDelay = settings.get("tcp_no_delay");
        String tcpKeepAlive = settings.get("tcp_keep_alive");
        boolean reuseAddress = settings.getAsBoolean("reuse_address", NetworkUtils.defaultReuseAddress());
        ByteSizeValue tcpSendBufferSize = TCP_SEND_BUFFER_SIZE.getDefault(settings);
        ByteSizeValue tcpReceiveBufferSize = TCP_RECEIVE_BUFFER_SIZE.getDefault(settings);

        if (logger.isDebugEnabled()) {
            logger.debug("using profile[{}], worker_count[{}], port[{}], bind_host[{}], publish_host[{}], compress[{}], "
                    + "connect_timeout[{}], connections_per_node[{}/{}/{}/{}/{}], receive_predictor[{}->{}]",
                name, workerCount, port, bindHost, publishHost, compress, connectTimeout, connectionsPerNodeRecovery,
                connectionsPerNodeBulk, connectionsPerNodeReg, connectionsPerNodeState, connectionsPerNodePing, receivePredictorMin,
                receivePredictorMax);
        }

        final ThreadFactory bossFactory = daemonThreadFactory(this.settings, HTTP_SERVER_BOSS_THREAD_NAME_PREFIX, name);
        final ThreadFactory workerFactory = daemonThreadFactory(this.settings, HTTP_SERVER_WORKER_THREAD_NAME_PREFIX, name);
        ServerBootstrap serverBootstrap;
        if (blockingServer) {
            serverBootstrap = new ServerBootstrap(new OioServerSocketChannelFactory(
                Executors.newCachedThreadPool(bossFactory),
                Executors.newCachedThreadPool(workerFactory)
            ));
        } else {
            serverBootstrap = new ServerBootstrap(new NioServerSocketChannelFactory(
                Executors.newCachedThreadPool(bossFactory),
                Executors.newCachedThreadPool(workerFactory),
                workerCount));
        }
        serverBootstrap.setPipelineFactory(configureServerChannelPipelineFactory(name, settings));
        if (!"default".equals(tcpNoDelay)) {
            serverBootstrap.setOption("child.tcpNoDelay", Booleans.parseBoolean(tcpNoDelay, null));
        }
        if (!"default".equals(tcpKeepAlive)) {
            serverBootstrap.setOption("child.keepAlive", Booleans.parseBoolean(tcpKeepAlive, null));
        }
        if (tcpSendBufferSize != null && tcpSendBufferSize.bytes() > 0) {
            serverBootstrap.setOption("child.sendBufferSize", tcpSendBufferSize.bytes());
        }
        if (tcpReceiveBufferSize != null && tcpReceiveBufferSize.bytes() > 0) {
            serverBootstrap.setOption("child.receiveBufferSize", tcpReceiveBufferSize.bytes());
        }
        serverBootstrap.setOption("receiveBufferSizePredictorFactory", receiveBufferSizePredictorFactory);
        serverBootstrap.setOption("child.receiveBufferSizePredictorFactory", receiveBufferSizePredictorFactory);
        serverBootstrap.setOption("reuseAddress", reuseAddress);
        serverBootstrap.setOption("child.reuseAddress", reuseAddress);
        serverBootstraps.put(name, serverBootstrap);
    }

    protected final void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
       onException(ctx.getChannel(), e.getCause());
    }

    @Override
    public long serverOpen() {
        OpenChannelsHandler channels = serverOpenChannels;
        return channels == null ? 0 : channels.numberOfOpenChannels();
    }

    protected NodeChannels connectToChannelsLight(DiscoveryNode node) {
        InetSocketAddress address = ((InetSocketTransportAddress) node.getAddress()).address();
        ChannelFuture connect = clientBootstrap.connect(address);
        connect.awaitUninterruptibly((long) (connectTimeout.millis() * 1.5));
        if (!connect.isSuccess()) {
            throw new ConnectTransportException(node, "connect_timeout[" + connectTimeout + "]", connect.getCause());
        }
        Channel[] channels = new Channel[1];
        channels[0] = connect.getChannel();
        channels[0].getCloseFuture().addListener(new ChannelCloseListener(node));
        return new NodeChannels(channels, channels, channels, channels, channels);
    }

    protected NodeChannels connectToChannels(DiscoveryNode node) {
        final NodeChannels nodeChannels = new NodeChannels(new Channel[connectionsPerNodeRecovery], new Channel[connectionsPerNodeBulk],
            new Channel[connectionsPerNodeReg], new Channel[connectionsPerNodeState],
            new Channel[connectionsPerNodePing]);
        boolean success = false;
        try {
            ChannelFuture[] connectRecovery = new ChannelFuture[nodeChannels.recovery.length];
            ChannelFuture[] connectBulk = new ChannelFuture[nodeChannels.bulk.length];
            ChannelFuture[] connectReg = new ChannelFuture[nodeChannels.reg.length];
            ChannelFuture[] connectState = new ChannelFuture[nodeChannels.state.length];
            ChannelFuture[] connectPing = new ChannelFuture[nodeChannels.ping.length];
            InetSocketAddress address = ((InetSocketTransportAddress) node.getAddress()).address();
            for (int i = 0; i < connectRecovery.length; i++) {
                connectRecovery[i] = clientBootstrap.connect(address);
            }
            for (int i = 0; i < connectBulk.length; i++) {
                connectBulk[i] = clientBootstrap.connect(address);
            }
            for (int i = 0; i < connectReg.length; i++) {
                connectReg[i] = clientBootstrap.connect(address);
            }
            for (int i = 0; i < connectState.length; i++) {
                connectState[i] = clientBootstrap.connect(address);
            }
            for (int i = 0; i < connectPing.length; i++) {
                connectPing[i] = clientBootstrap.connect(address);
            }

            try {
                for (int i = 0; i < connectRecovery.length; i++) {
                    connectRecovery[i].awaitUninterruptibly((long) (connectTimeout.millis() * 1.5));
                    if (!connectRecovery[i].isSuccess()) {
                        throw new ConnectTransportException(node, "connect_timeout[" + connectTimeout + "]", connectRecovery[i].getCause());
                    }
                    nodeChannels.recovery[i] = connectRecovery[i].getChannel();
                    nodeChannels.recovery[i].getCloseFuture().addListener(new ChannelCloseListener(node));
                }

                for (int i = 0; i < connectBulk.length; i++) {
                    connectBulk[i].awaitUninterruptibly((long) (connectTimeout.millis() * 1.5));
                    if (!connectBulk[i].isSuccess()) {
                        throw new ConnectTransportException(node, "connect_timeout[" + connectTimeout + "]", connectBulk[i].getCause());
                    }
                    nodeChannels.bulk[i] = connectBulk[i].getChannel();
                    nodeChannels.bulk[i].getCloseFuture().addListener(new ChannelCloseListener(node));
                }

                for (int i = 0; i < connectReg.length; i++) {
                    connectReg[i].awaitUninterruptibly((long) (connectTimeout.millis() * 1.5));
                    if (!connectReg[i].isSuccess()) {
                        throw new ConnectTransportException(node, "connect_timeout[" + connectTimeout + "]", connectReg[i].getCause());
                    }
                    nodeChannels.reg[i] = connectReg[i].getChannel();
                    nodeChannels.reg[i].getCloseFuture().addListener(new ChannelCloseListener(node));
                }

                for (int i = 0; i < connectState.length; i++) {
                    connectState[i].awaitUninterruptibly((long) (connectTimeout.millis() * 1.5));
                    if (!connectState[i].isSuccess()) {
                        throw new ConnectTransportException(node, "connect_timeout[" + connectTimeout + "]", connectState[i].getCause());
                    }
                    nodeChannels.state[i] = connectState[i].getChannel();
                    nodeChannels.state[i].getCloseFuture().addListener(new ChannelCloseListener(node));
                }

                for (int i = 0; i < connectPing.length; i++) {
                    connectPing[i].awaitUninterruptibly((long) (connectTimeout.millis() * 1.5));
                    if (!connectPing[i].isSuccess()) {
                        throw new ConnectTransportException(node, "connect_timeout[" + connectTimeout + "]", connectPing[i].getCause());
                    }
                    nodeChannels.ping[i] = connectPing[i].getChannel();
                    nodeChannels.ping[i].getCloseFuture().addListener(new ChannelCloseListener(node));
                }

                if (nodeChannels.recovery.length == 0) {
                    if (nodeChannels.bulk.length > 0) {
                        nodeChannels.recovery = nodeChannels.bulk;
                    } else {
                        nodeChannels.recovery = nodeChannels.reg;
                    }
                }
                if (nodeChannels.bulk.length == 0) {
                    nodeChannels.bulk = nodeChannels.reg;
                }
            } catch (RuntimeException e) {
                // clean the futures
                List<ChannelFuture> futures = new ArrayList<>();
                futures.addAll(Arrays.asList(connectRecovery));
                futures.addAll(Arrays.asList(connectBulk));
                futures.addAll(Arrays.asList(connectReg));
                futures.addAll(Arrays.asList(connectState));
                futures.addAll(Arrays.asList(connectPing));
                for (ChannelFuture future : Collections.unmodifiableList(futures)) {
                    future.cancel();
                    if (future.getChannel() != null && future.getChannel().isOpen()) {
                        try {
                            future.getChannel().close();
                        } catch (Exception e1) {
                            // ignore
                        }
                    }
                }
                throw e;
            }
            success = true;
        } finally {
            if (success == false) {
                nodeChannels.close();
            }
        }
        return nodeChannels;
    }

    public ChannelPipelineFactory configureClientChannelPipelineFactory() {
        return new ClientChannelPipelineFactory(this);
    }

    protected static class ClientChannelPipelineFactory implements ChannelPipelineFactory {
        protected final NettyTransport nettyTransport;

        public ClientChannelPipelineFactory(NettyTransport nettyTransport) {
            this.nettyTransport = nettyTransport;
        }

        @Override
        public ChannelPipeline getPipeline() throws Exception {
            ChannelPipeline channelPipeline = Channels.pipeline();
            SizeHeaderFrameDecoder sizeHeader = new SizeHeaderFrameDecoder();
            if (nettyTransport.maxCumulationBufferCapacity.bytes() >= 0) {
                if (nettyTransport.maxCumulationBufferCapacity.bytes() > Integer.MAX_VALUE) {
                    sizeHeader.setMaxCumulationBufferCapacity(Integer.MAX_VALUE);
                } else {
                    sizeHeader.setMaxCumulationBufferCapacity((int) nettyTransport.maxCumulationBufferCapacity.bytes());
                }
            }
            if (nettyTransport.maxCompositeBufferComponents != -1) {
                sizeHeader.setMaxCumulationBufferComponents(nettyTransport.maxCompositeBufferComponents);
            }
            channelPipeline.addLast("size", sizeHeader);
            // using a dot as a prefix means, this cannot come from any settings parsed
            channelPipeline.addLast("dispatcher", new NettyMessageChannelHandler(nettyTransport, ".client"));
            return channelPipeline;
        }
    }

    public ChannelPipelineFactory configureServerChannelPipelineFactory(String name, Settings settings) {
        return new ServerChannelPipelineFactory(this, name, settings);
    }

    protected static class ServerChannelPipelineFactory implements ChannelPipelineFactory {

        protected final NettyTransport nettyTransport;
        protected final String name;
        protected final Settings settings;

        public ServerChannelPipelineFactory(NettyTransport nettyTransport, String name, Settings settings) {
            this.nettyTransport = nettyTransport;
            this.name = name;
            this.settings = settings;
        }

        @Override
        public ChannelPipeline getPipeline() throws Exception {
            ChannelPipeline channelPipeline = Channels.pipeline();
            channelPipeline.addLast("openChannels", nettyTransport.serverOpenChannels);
            SizeHeaderFrameDecoder sizeHeader = new SizeHeaderFrameDecoder();
            if (nettyTransport.maxCumulationBufferCapacity.bytes() > 0) {
                if (nettyTransport.maxCumulationBufferCapacity.bytes() > Integer.MAX_VALUE) {
                    sizeHeader.setMaxCumulationBufferCapacity(Integer.MAX_VALUE);
                } else {
                    sizeHeader.setMaxCumulationBufferCapacity((int) nettyTransport.maxCumulationBufferCapacity.bytes());
                }
            }
            if (nettyTransport.maxCompositeBufferComponents != -1) {
                sizeHeader.setMaxCumulationBufferComponents(nettyTransport.maxCompositeBufferComponents);
            }
            channelPipeline.addLast("size", sizeHeader);
            channelPipeline.addLast("dispatcher", new NettyMessageChannelHandler(nettyTransport, name));
            return channelPipeline;
        }
    }

    protected class ChannelCloseListener implements ChannelFutureListener {

        private final DiscoveryNode node;

        private ChannelCloseListener(DiscoveryNode node) {
            this.node = node;
        }

        @Override
        public void operationComplete(final ChannelFuture future) throws Exception {
            NodeChannels nodeChannels = connectedNodes.get(node);
            if (nodeChannels != null && nodeChannels.hasChannel(future.getChannel())) {
                threadPool.generic().execute(() -> disconnectFromNode(node, future.getChannel(), "channel closed event"));
            }
        }
    }

    protected void sendMessage(Channel channel, BytesReference reference, Runnable sendListener, boolean close) {
        final ChannelFuture future = channel.write(NettyUtils.toChannelBuffer(reference));
        if (close) {
            future.addListener(f -> {
                try {
                    sendListener.run();
                } finally {
                    f.getChannel().close();
                }
            });
        } else {
            future.addListener(future1 -> sendListener.run());
        }
    }

    @Override @SwallowsExceptions(reason = "?")
    protected void closeChannels(List<Channel> channels) {
        List<ChannelFuture> futures = new ArrayList<>();

        for (Channel channel : channels) {
            try {
                if (channel != null && channel.isOpen()) {
                    futures.add(channel.close());
                }
            } catch (Exception e) {
                logger.trace("failed to close channel", e);
            }
        }
        for (ChannelFuture future : futures) {
            future.awaitUninterruptibly();
        }
    }

    @Override
    protected InetSocketAddress getLocalAddress(Channel channel) {
        return (InetSocketAddress) channel.getLocalAddress();
    }

    @Override
    protected Channel bind(String name, InetSocketAddress address) {
        return serverBootstraps.get(name).bind(address);
    }

    ScheduledPing getPing() {
        return scheduledPing;
    }

    @Override
    protected boolean isOpen(Channel channel) {
        return channel.isOpen();
    }

    @Override
    protected void stopInternal() {
        Releasables.close(serverOpenChannels, () ->{
            for (Map.Entry<String, ServerBootstrap> entry : serverBootstraps.entrySet()) {
                String name = entry.getKey();
                ServerBootstrap serverBootstrap = entry.getValue();
                try {
                    serverBootstrap.releaseExternalResources();
                } catch (Throwable t) {
                    logger.debug("Error closing serverBootstrap for profile [{}]", t, name);
                }
            }
            serverBootstraps.clear();
            if (clientBootstrap != null) {
                clientBootstrap.releaseExternalResources();
                clientBootstrap = null;
            }
        });
    }

    @Override
    public Message<Channel> prepareSend(Version nodeVersion, TransportMessage message, StreamOutput stream,
                                        ReleasableBytesStream writtenBytes) throws IOException {
            // it might be nice to somehow generalize this optimization, maybe a smart "paged" bytes output
            // that create paged channel buffers, but its tricky to know when to do it (where this option is
            // more explicit).
            if (message instanceof BytesTransportRequest) {
                BytesTransportRequest bRequest = (BytesTransportRequest) message;
                assert nodeVersion.equals(bRequest.version());
                bRequest.writeThin(stream);
                stream.close();
                ReleasablePagedBytesReference bytes = writtenBytes.bytes();
                ChannelBuffer headerBuffer = NettyUtils.toChannelBuffer(bytes);
                ChannelBuffer contentBuffer = NettyUtils.toChannelBuffer(bRequest.bytes());
                ChannelBuffer buffer = ChannelBuffers.wrappedBuffer(NettyUtils.DEFAULT_GATHERING, headerBuffer, contentBuffer);
                return new NettyMessage(buffer);
            } else {
                return super.prepareSend(nodeVersion, message, stream, writtenBytes);
            }
    }

    @Override
    public Message<Channel> prepareSend(Version nodeVersion, BytesReference bytesReference) {
        return new NettyMessage(NettyUtils.toChannelBuffer(bytesReference));
    }

    @Override
    public boolean canCompress(TransportRequest request) {
        return super.canCompress(request) && (!(request instanceof BytesTransportRequest));
    }

    private class NettyMessage implements Message<Channel> {
        private final ChannelBuffer buffer;

        public NettyMessage(ChannelBuffer buffer) {
            this.buffer = buffer;
        }

        public StreamOutput getHeaderOutput() {
            return new ChannelBufferStreamOutput(buffer);
        }

        public int size() {
            return buffer.readableBytes();
        }

        @Override
        public void send(Channel channel, Runnable onRequestSent) {
            ChannelFuture future = channel.write(buffer);
            ChannelFutureListener channelFutureListener = f -> onRequestSent.run();
            future.addListener(channelFutureListener);
        }
    }

    private final static class ChannelBufferStreamOutput extends StreamOutput {

        private final ChannelBuffer buffer;
        private int offset;

        public ChannelBufferStreamOutput(ChannelBuffer buffer) {
            this.buffer = buffer;
            this.offset = buffer.readerIndex();
        }

        @Override
        public void writeByte(byte b) throws IOException {
            buffer.setByte(offset++, b);
        }

        @Override
        public void writeBytes(byte[] b, int offset, int length) throws IOException {
            buffer.setBytes(this.offset, b, offset, length);
            this.offset += length;
        }

        @Override
        public void flush() throws IOException {
        }

        @Override
        public void close() throws IOException {
        }

        @Override
        public void reset() throws IOException {
            throw new UnsupportedOperationException();
        }
    }
}
