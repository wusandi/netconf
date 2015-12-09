/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.netconf.topology.impl;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.TypedActor;
import akka.actor.TypedProps;
import akka.cluster.Cluster;
import akka.dispatch.OnComplete;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.opendaylight.controller.md.sal.dom.api.DOMNotification;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcService;
import org.opendaylight.netconf.sal.connect.api.RemoteDeviceHandler;
import org.opendaylight.netconf.sal.connect.netconf.listener.NetconfDeviceCapabilities;
import org.opendaylight.netconf.sal.connect.netconf.listener.NetconfSessionPreferences;
import org.opendaylight.netconf.topology.NetconfTopology;
import org.opendaylight.netconf.topology.NodeManagerCallback;
import org.opendaylight.netconf.topology.RoleChangeStrategy;
import org.opendaylight.netconf.topology.TopologyManager;
import org.opendaylight.netconf.topology.pipeline.TopologyMountPointFacade.ConnectionStatusListenerRegistration;
import org.opendaylight.netconf.topology.util.BaseTopologyManager;
import org.opendaylight.yang.gen.v1.urn.opendaylight.netconf.node.topology.rev150114.NetconfNode;
import org.opendaylight.yang.gen.v1.urn.opendaylight.netconf.node.topology.rev150114.NetconfNodeBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.netconf.node.topology.rev150114.NetconfNodeConnectionStatus.ConnectionStatus;
import org.opendaylight.yang.gen.v1.urn.opendaylight.netconf.node.topology.rev150114.netconf.node.connection.status.AvailableCapabilitiesBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.netconf.node.topology.rev150114.netconf.node.connection.status.ClusteredConnectionStatusBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.netconf.node.topology.rev150114.netconf.node.connection.status.UnavailableCapabilities;
import org.opendaylight.yang.gen.v1.urn.opendaylight.netconf.node.topology.rev150114.netconf.node.connection.status.UnavailableCapabilitiesBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.netconf.node.topology.rev150114.netconf.node.connection.status.clustered.connection.status.NodeStatus.Status;
import org.opendaylight.yang.gen.v1.urn.opendaylight.netconf.node.topology.rev150114.netconf.node.connection.status.clustered.connection.status.NodeStatusBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.netconf.node.topology.rev150114.netconf.node.connection.status.unavailable.capabilities.UnavailableCapability;
import org.opendaylight.yang.gen.v1.urn.opendaylight.netconf.node.topology.rev150114.netconf.node.connection.status.unavailable.capabilities.UnavailableCapability.FailureReason;
import org.opendaylight.yang.gen.v1.urn.opendaylight.netconf.node.topology.rev150114.netconf.node.connection.status.unavailable.capabilities.UnavailableCapabilityBuilder;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NodeId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.NodeBuilder;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;

public class NetconfNodeManagerCallback implements NodeManagerCallback, RemoteDeviceHandler<NetconfSessionPreferences>{

    private static final Logger LOG = LoggerFactory.getLogger(NetconfNodeManagerCallback.class);

    public static final Function<Entry<QName, FailureReason>, UnavailableCapability> UNAVAILABLE_CAPABILITY_TRANSFORMER = new Function<Entry<QName, FailureReason>, UnavailableCapability>() {
        @Override
        public UnavailableCapability apply(final Entry<QName, FailureReason> input) {
            return new UnavailableCapabilityBuilder()
                    .setCapability(input.getKey().toString())
                    .setFailureReason(input.getValue()).build();
        }
    };
    public static final Function<QName, String> AVAILABLE_CAPABILITY_TRANSFORMER = new Function<QName, String>() {
        @Override
        public String apply(QName qName) {
            // intern string representation of a capability to avoid duplicates
            return qName.toString().intern();
        }
    };

    private static final String UNKNOWN_REASON = "Unknown reason";

    private boolean isMaster = false;
    private ClusteredNetconfTopology topologyDispatcher;
    private final ActorSystem actorSystem;
    private final Cluster clusterExtension;

    private final RoleChangeStrategy roleChangeStrategy;

    private String nodeId;
    private String topologyId;
    private TopologyManager topologyManager;

    private Node currentConfig;
    private Node currentOperationalNode;

    private ConnectionStatusListenerRegistration registration = null;

    public NetconfNodeManagerCallback(final String nodeId,
                                      final String topologyId,
                                      final ActorSystem actorSystem,
                                      final NetconfTopology topologyDispatcher,
                                      final RoleChangeStrategy roleChangeStrategy) {
        this.nodeId = nodeId;
        this.topologyId = topologyId;
        this.actorSystem = actorSystem;
        this.clusterExtension = Cluster.get(actorSystem);
        this.topologyDispatcher = (ClusteredNetconfTopology) topologyDispatcher;
        this.roleChangeStrategy = roleChangeStrategy;

        final Future<ActorRef> topologyRefFuture = actorSystem.actorSelection("/user/" + topologyId).resolveOne(FiniteDuration.create(10L, TimeUnit.SECONDS));
        topologyRefFuture.onComplete(new OnComplete<ActorRef>() {
            @Override
            public void onComplete(Throwable throwable, ActorRef actorRef) throws Throwable {
                if (throwable != null) {
                    LOG.warn("Unable to resolve actor for path: {} ", "/user/" + topologyId, throwable);

                }

                LOG.debug("Actor ref for path {} resolved", "/user/" + topologyId);
                topologyManager = TypedActor.get(actorSystem).typedActorOf(new TypedProps<>(TopologyManager.class, BaseTopologyManager.class), actorRef);
            }
        }, actorSystem.dispatcher());
    }


    @Nonnull
    @Override public Node getInitialState(@Nonnull final NodeId nodeId,
                                          @Nonnull final Node configNode) {
        final NetconfNode netconfNode = configNode.getAugmentation(NetconfNode.class);

        final Node initialNode = new NodeBuilder()
                .setNodeId(nodeId)
                .addAugmentation(NetconfNode.class,
                        new NetconfNodeBuilder()
                                .setHost(netconfNode.getHost())
                                .setPort(netconfNode.getPort())
                                .setConnectionStatus(ConnectionStatus.Connecting)
                                .setClusteredConnectionStatus(
                                        new ClusteredConnectionStatusBuilder()
                                                .setNodeStatus(
                                                        Lists.newArrayList(
                                                                new NodeStatusBuilder()
                                                                        .setNode(clusterExtension.selfAddress().toString())
                                                                        .setStatus(Status.Unavailable)
                                                                        .build()))
                                                .build())
                                .build())
                .build();

        if (currentOperationalNode == null) {
            currentOperationalNode = initialNode;
        }

        return initialNode;
    }

    @Nonnull @Override public Node getFailedState(@Nonnull final NodeId nodeId,
                                                  @Nonnull final Node configNode) {
        final NetconfNode netconfNode = configNode.getAugmentation(NetconfNode.class);

        return new NodeBuilder()
                .setNodeId(nodeId)
                .addAugmentation(NetconfNode.class,
                        new NetconfNodeBuilder()
                                .setHost(netconfNode.getHost())
                                .setPort(netconfNode.getPort())
                                .setConnectionStatus(ConnectionStatus.UnableToConnect)
                                .setClusteredConnectionStatus(
                                        new ClusteredConnectionStatusBuilder()
                                                .setNodeStatus(
                                                        Collections.singletonList(
                                                                new NodeStatusBuilder()
                                                                        .setNode(clusterExtension.selfAddress().toString())
                                                                        .setStatus(Status.Failed)
                                                                        .build()))
                                                .build())
                                .build())
                .build();
    }

    @Nonnull @Override public ListenableFuture<Node> onNodeCreated(@Nonnull final NodeId nodeId,
                                                                   @Nonnull final Node configNode) {
        this.nodeId = nodeId.getValue();
        this.currentConfig = configNode;
        // set initial state before anything happens
        this.currentOperationalNode = getInitialState(nodeId, configNode);

        // connect magic, send config into the netconf pipeline through topo dispatcher
        final ListenableFuture<NetconfDeviceCapabilities> connectionFuture = topologyDispatcher.connectNode(nodeId, configNode);

        Futures.addCallback(connectionFuture, new FutureCallback<NetconfDeviceCapabilities>() {
            @Override
            public void onSuccess(@Nullable NetconfDeviceCapabilities result) {
                registration = topologyDispatcher.registerConnectionStatusListener(nodeId, NetconfNodeManagerCallback.this);
            }

            @Override
            public void onFailure(Throwable t) {
                LOG.error("Connection to device failed", t);
            }
        });

        final NetconfNode netconfNode = configNode.getAugmentation(NetconfNode.class);

        // transform future result into state that gets written into datastore
        return Futures.transform(connectionFuture, new Function<NetconfDeviceCapabilities, Node>() {
            @Nullable
            @Override
            public Node apply(NetconfDeviceCapabilities input) {
                // build state data
                currentOperationalNode = new NodeBuilder().setNodeId(nodeId)
                        .addAugmentation(NetconfNode.class,
                                new NetconfNodeBuilder()
                                        .setConnectionStatus(ConnectionStatus.Connected)
                                        .setClusteredConnectionStatus(
                                                new ClusteredConnectionStatusBuilder()
                                                        .setNodeStatus(
                                                                Collections.singletonList(
                                                                        new NodeStatusBuilder()
                                                                                .setNode(clusterExtension.selfAddress().toString())
                                                                                .setStatus(Status.Connected)
                                                                                .build()))
                                                        .build())
                                        .setHost(netconfNode.getHost())
                                        .setPort(netconfNode.getPort())
                                        .setAvailableCapabilities(new AvailableCapabilitiesBuilder().build())
                                        .setUnavailableCapabilities(new UnavailableCapabilitiesBuilder().build())
                                        .build()).build();
                return currentOperationalNode;
            }
        });
    }

    @Nonnull
    @Override
    public ListenableFuture<Node> onNodeUpdated(@Nonnull final NodeId nodeId,
                                                @Nonnull final Node configNode) {
        // first disconnect this node
        topologyDispatcher.unregisterMountPoint(nodeId);
        registration.close();
        topologyDispatcher.disconnectNode(nodeId);

        // now reinit this connection with new settings
        final ListenableFuture<NetconfDeviceCapabilities> connectionFuture = topologyDispatcher.connectNode(nodeId, configNode);

        Futures.addCallback(connectionFuture, new FutureCallback<NetconfDeviceCapabilities>() {
            @Override
            public void onSuccess(@Nullable NetconfDeviceCapabilities result) {
                registration = topologyDispatcher.registerConnectionStatusListener(nodeId, NetconfNodeManagerCallback.this);
            }

            @Override
            public void onFailure(Throwable t) {
                LOG.error("Connection to device failed", t);
            }
        });

        final NetconfNode netconfNode = configNode.getAugmentation(NetconfNode.class);

        return Futures.transform(connectionFuture, new Function<NetconfDeviceCapabilities, Node>() {
            @Nullable
            @Override
            public Node apply(NetconfDeviceCapabilities input) {
                // build state data
                return new NodeBuilder()
                        .setNodeId(nodeId)
                        .addAugmentation(NetconfNode.class,
                                new NetconfNodeBuilder()
                                        .setConnectionStatus(ConnectionStatus.Connected)
                                        .setClusteredConnectionStatus(
                                                new ClusteredConnectionStatusBuilder()
                                                        .setNodeStatus(
                                                                Collections.singletonList(
                                                                        new NodeStatusBuilder()
                                                                                .setNode(clusterExtension.selfAddress().toString())
                                                                                .setStatus(Status.Connected)
                                                                                .build()))
                                                        .build())
                                        .setHost(netconfNode.getHost())
                                        .setPort(netconfNode.getPort())
                                        .setAvailableCapabilities(new AvailableCapabilitiesBuilder().build())
                                        .setUnavailableCapabilities(new UnavailableCapabilitiesBuilder().build())
                                        .build())
                                .build();
            }
        });
    }

    @Nonnull @Override public ListenableFuture<Void> onNodeDeleted(@Nonnull final NodeId nodeId) {
        // cleanup and disconnect
        topologyDispatcher.unregisterMountPoint(nodeId);
        registration.close();
        roleChangeStrategy.unregisterRoleCandidate();
        return topologyDispatcher.disconnectNode(nodeId);
    }

    @Nonnull
    @Override
    public ListenableFuture<Node> getCurrentStatusForNode(@Nonnull NodeId nodeId) {
        LOG.debug("Getting current status for node: {} status: {}", nodeId, currentOperationalNode);
        return Futures.immediateFuture(currentOperationalNode);
    }

    @Override
    public void onRoleChanged(final RoleChangeDTO roleChangeDTO) {
        if (roleChangeDTO.isOwner() && roleChangeDTO.wasOwner()) {
            return;
        }
        isMaster = roleChangeDTO.isOwner();
        //TODO instead of registering mount point, init remote schema repo when its done
        if (isMaster) {
            // unregister old mountPoint if ownership changed, register a new one
            topologyDispatcher.registerMountPoint(new NodeId(nodeId));
        } else {
            topologyDispatcher.unregisterMountPoint(new NodeId(nodeId));
        }
    }

    @Override
    public void onDeviceConnected(final SchemaContext remoteSchemaContext, final NetconfSessionPreferences netconfSessionPreferences, final DOMRpcService deviceRpc) {
        // we need to notify the higher level that something happened, get a current status from all other nodes, and aggregate a new result
        LOG.debug("onDeviceConnected received, registering role candidate");
        roleChangeStrategy.registerRoleCandidate(this);
        List<String> capabilityList = new ArrayList<>();
        capabilityList.addAll(netconfSessionPreferences.getNetconfDeviceCapabilities().getNonModuleBasedCapabilities());
        capabilityList.addAll(FluentIterable.from(netconfSessionPreferences.getNetconfDeviceCapabilities().getResolvedCapabilities()).transform(AVAILABLE_CAPABILITY_TRANSFORMER).toList());
        final AvailableCapabilitiesBuilder avCapabalitiesBuilder = new AvailableCapabilitiesBuilder();
        avCapabalitiesBuilder.setAvailableCapability(capabilityList);

        final UnavailableCapabilities unavailableCapabilities =
                new UnavailableCapabilitiesBuilder().setUnavailableCapability(FluentIterable.from(netconfSessionPreferences.getNetconfDeviceCapabilities().getUnresolvedCapabilites().entrySet())
                        .transform(UNAVAILABLE_CAPABILITY_TRANSFORMER).toList()).build();

        final NetconfNode netconfNode = currentConfig.getAugmentation(NetconfNode.class);
        currentOperationalNode = new NodeBuilder().setNodeId(new NodeId(nodeId))
                .addAugmentation(NetconfNode.class,
                        new NetconfNodeBuilder()
                                .setConnectionStatus(ConnectionStatus.Connected)
                                .setClusteredConnectionStatus(
                                        new ClusteredConnectionStatusBuilder()
                                                .setNodeStatus(
                                                        Collections.singletonList(
                                                                new NodeStatusBuilder()
                                                                        .setNode(clusterExtension.selfAddress().toString())
                                                                        .setStatus(Status.Connected)
                                                                        .build()))
                                                .build())
                                .setHost(netconfNode.getHost())
                                .setPort(netconfNode.getPort())
                                .setAvailableCapabilities(avCapabalitiesBuilder.build())
                                .setUnavailableCapabilities(unavailableCapabilities)
                                .build())
                .build();
        // TODO need to implement forwarding of this msg to master
        topologyManager.notifyNodeStatusChange(new NodeId(nodeId));
    }

    @Override
    public void onDeviceDisconnected() {
        // we need to notify the higher level that something happened, get a current status from all other nodes, and aggregate a new result
        // no need to remove mountpoint, we should receive onRoleChanged callback after unregistering from election that unregisters the mountpoint
        LOG.debug("onDeviceDisconnected received, unregistering role candidate");
        topologyDispatcher.unregisterMountPoint(currentOperationalNode.getNodeId());
        roleChangeStrategy.unregisterRoleCandidate();
        final NetconfNode netconfNode = currentConfig.getAugmentation(NetconfNode.class);
        currentOperationalNode = new NodeBuilder().setNodeId(new NodeId(nodeId))
                .addAugmentation(NetconfNode.class,
                        new NetconfNodeBuilder()
                                .setConnectionStatus(ConnectionStatus.Connecting)
                                .setClusteredConnectionStatus(
                                        new ClusteredConnectionStatusBuilder()
                                                .setNodeStatus(
                                                        Collections.singletonList(
                                                                new NodeStatusBuilder()
                                                                        .setNode(clusterExtension.selfAddress().toString())
                                                                        .setStatus(Status.Unavailable)
                                                                        .build()))
                                                .build())
                                .setHost(netconfNode.getHost())
                                .setPort(netconfNode.getPort())
                                .build()).build();
        // TODO need to implement forwarding of this msg to master
        topologyManager.notifyNodeStatusChange(new NodeId(nodeId));
    }

    @Override
    public void onDeviceFailed(Throwable throwable) {
        // we need to notify the higher level that something happened, get a current status from all other nodes, and aggregate a new result
        // no need to remove mountpoint, we should receive onRoleChanged callback after unregistering from election that unregisters the mountpoint
        LOG.debug("onDeviceFailed received");
        String reason = (throwable != null && throwable.getMessage() != null) ? throwable.getMessage() : UNKNOWN_REASON;

        roleChangeStrategy.unregisterRoleCandidate();
        currentOperationalNode = new NodeBuilder().setNodeId(new NodeId(nodeId))
                .addAugmentation(NetconfNode.class,
                        new NetconfNodeBuilder()
                                .setConnectionStatus(ConnectionStatus.UnableToConnect)
                                .setClusteredConnectionStatus(
                                        new ClusteredConnectionStatusBuilder()
                                                .setNodeStatus(
                                                        Collections.singletonList(
                                                                new NodeStatusBuilder()
                                                                        .setNode(clusterExtension.selfAddress().toString())
                                                                        .setStatus(Status.Failed)
                                                                        .build()))
                                                .build())
                                .setConnectedMessage(reason)
                                .build()).build();
        topologyManager.notifyNodeStatusChange(new NodeId(nodeId));
    }


    @Override
    public void onNotification(DOMNotification domNotification) {
        //NOOP
    }

    @Override
    public void close() {
        //NOOP
    }

    @Override
    public void onReceive(Object o, ActorRef actorRef) {

    }
}