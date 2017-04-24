/*
 * Copyright (c) 2017 Cisco Systems Inc and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.unimgr.mef.nrp.ovs.activator;

import org.junit.Before;
import org.junit.Test;
import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.md.sal.binding.test.AbstractDataBrokerTest;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.unimgr.mef.nrp.api.EndPoint;
import org.opendaylight.unimgr.mef.nrp.common.ResourceNotAvailableException;
import org.opendaylight.unimgr.mef.nrp.ovs.FlowTopologyTestUtils;
import org.opendaylight.unimgr.mef.nrp.ovs.OpenFlowTopologyTestUtils;
import org.opendaylight.unimgr.mef.nrp.ovs.OvsdbTopologyTestUtils;
import org.opendaylight.unimgr.mef.nrp.ovs.util.OpenFlowUtils;
import org.opendaylight.unimgr.utils.MdsalUtils;
import org.opendaylight.yang.gen.v1.urn.mef.yang.nrm_connectivity.rev170227.PositiveInteger;
import org.opendaylight.yang.gen.v1.urn.mef.yang.nrm_connectivity.rev170227.cg.eth.frame.flow.cpa.aspec.CeVlanIdList;
import org.opendaylight.yang.gen.v1.urn.mef.yang.nrm_connectivity.rev170227.vlan.id.listing.VlanIdList;
import org.opendaylight.yang.gen.v1.urn.mef.yang.nrp_interface.rev170227.NrpCreateConnectivityServiceEndPointAttrs;
import org.opendaylight.yang.gen.v1.urn.mef.yang.nrp_interface.rev170227.nrp.create.connectivity.service.end.point.attrs.NrpCgEthFrameFlowCpaAspec;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapicommon.rev170227.UniversalId;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapiconnectivity.rev170227.ConnectivityServiceEndPoint;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.tables.Table;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.tables.table.Flow;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.Nodes;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.node.NodeConnector;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.ovsdb.rev150105.OvsdbTerminationPointAugmentation;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Link;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.node.TerminationPoint;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author marek.ryznar@amartus.com
 */
public class OvsActivatorTest extends AbstractDataBrokerTest{

    private DataBroker dataBroker;
    private OvsActivator ovsActivator;
    private static final String port1Name = "sip:ovs-node:s1:s1-eth1";
    private static final String port2Name = "sip:ovs-node:s5:s5-eth1";
    private static final String ofPort1Name = "openflow:1";
    private static final String ofPort2Name = "openflow:5";
    private static final Integer expectedVlanId = 200;
    private static final String serviceId = "serviceId";

    private static final String interswitchName = "interswitch-openflow";
    private static final String vlanName = "vlan-openflow";
    private static final String dropName = "default-DROP";

    @Before
    public void setUp(){
        //given
        dataBroker = getDataBroker();
        ovsActivator = new OvsActivator(dataBroker);
        OvsdbTopologyTestUtils.createOvsdbTopology(dataBroker);
        initTopologies();
        FlowTopologyTestUtils.createFlowTopology(dataBroker, getLinkList());
    }

    @Test
    public void testActivate(){
        //given
        List<String> of1InterwitchPorts = Arrays.asList("openflow:1:3", "openflow:1:4", "openflow:1:5");
        List<String> of2InterwitchPorts = Arrays.asList("openflow:5:3", "openflow:5:4");
        List<EndPoint> endPoints = prepareEndpoints();

        //when
        try {
            ovsActivator.activate(endPoints,serviceId);
        } catch (ResourceNotAvailableException e) {
            fail(e.getMessage());
        } catch (TransactionCommitFailedException e) {
            fail(e.getMessage());
        }

        //then
        Nodes nodes = readOpenFLowTopology(dataBroker);
        nodes.getNode()
                .forEach(node -> {
                    try {
                        Table t = OpenFlowUtils.getTable(node);
                        if(node.getKey().getId().getValue().equals(ofPort1Name)){
                            checkTable(t,of1InterwitchPorts);
                        } else if(node.getKey().getId().getValue().equals(ofPort2Name)){
                            checkTable(t,of2InterwitchPorts);
                        }
                    } catch (ResourceNotAvailableException e) {
                        fail(e.getMessage());
                    }
                });

        //when
        try {
            ovsActivator.deactivate(endPoints);
        } catch (TransactionCommitFailedException e) {
            fail(e.getMessage());
        } catch (ResourceNotAvailableException e) {
            fail(e.getMessage());
        }
    }

    private void checkTable(Table table, List<String> interswitchPorts){
        List<Flow> flows = table.getFlow();
        int interswitchPortCount = interswitchPorts.size();
        //vlan & interwitch + 1 vlan + 1 drop
        int flowCount = interswitchPortCount * 2 + 2;
        assertEquals(flowCount,flows.size());
        List<Flow> interswitchFlows = flows.stream()
                .filter(flow -> flow.getId().getValue().contains(interswitchName))
                .collect(Collectors.toList());
        assertEquals(interswitchPortCount,interswitchFlows.size());

        List<Flow> vlanFlows = flows.stream()
                .filter(flow -> flow.getId().getValue().contains(vlanName))
                .filter(flow -> flow.getMatch().getVlanMatch().getVlanId().getVlanId().getValue().equals(expectedVlanId))
                .collect(Collectors.toList());
        assertEquals(interswitchPortCount+1,vlanFlows.size());

        assertTrue(flows.stream().anyMatch(flow -> flow.getId().getValue().equals(dropName)));
    }

    private List<EndPoint> prepareEndpoints(){
        List<EndPoint> endPoints = new ArrayList<>();
        endPoints.add(mockEndPoint(port1Name));
        endPoints.add(mockEndPoint(port2Name));
        return endPoints;
    }

    private EndPoint mockEndPoint(String portName){
        ConnectivityServiceEndPoint connectivityServiceEndPoint = mock(ConnectivityServiceEndPoint.class);
        NrpCreateConnectivityServiceEndPointAttrs attrs = mock(NrpCreateConnectivityServiceEndPointAttrs.class);
        //UNI port mock
        when(connectivityServiceEndPoint.getServiceInterfacePoint())
                .thenReturn(new UniversalId(portName));

        //Vlan Id mock
        VlanIdList vlanIdList = mock(VlanIdList.class);
        when(vlanIdList.getVlanId())
                .thenReturn(PositiveInteger.getDefaultInstance(expectedVlanId.toString()));

        List<VlanIdList> vlanIds = new ArrayList<>();
        vlanIds.add(vlanIdList);

        CeVlanIdList ceVlanIdList = mock(CeVlanIdList.class);
        when(ceVlanIdList.getVlanIdList())
                .thenReturn(vlanIds);

        NrpCgEthFrameFlowCpaAspec nrpCgEthFrameFlowCpaAspec = mock(NrpCgEthFrameFlowCpaAspec.class);
        when(nrpCgEthFrameFlowCpaAspec.getCeVlanIdList())
                .thenReturn(ceVlanIdList);

        when(attrs.getNrpCgEthFrameFlowCpaAspec())
                .thenReturn(nrpCgEthFrameFlowCpaAspec);

        return new EndPoint(connectivityServiceEndPoint,attrs);
    }

    /**
     * Add 5 ovsdb bridges and suitable openflow nodes
     */
    private void initTopologies(){
        List<Node> bridges = new ArrayList<>();
        List<org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.Node> ofNodes = new ArrayList<>();
        bridges.add(createBridge("s1",5));
        bridges.add(createBridge("s2",2));
        bridges.add(createBridge("s3",4));
        bridges.add(createBridge("s4",3));
        bridges.add(createBridge("s5",4));

        bridges.forEach(node -> {
            OvsdbTopologyTestUtils.writeBridge(node,dataBroker);
            ofNodes.add(createOpenFlowNode(node));
        });

        OpenFlowTopologyTestUtils.createOpenFlowNodes(ofNodes,dataBroker);
    }

    private Node createBridge(String name, int portCount){
        List<TerminationPoint> tps = new ArrayList<>();
        IntStream.range(1,portCount+1)
                .forEach(i -> tps.add(OvsdbTopologyTestUtils.createTerminationPoint(name+"-eth"+i,Long.valueOf(i))));
        return OvsdbTopologyTestUtils.createBridge(name, tps);
    }

    private org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.Node createOpenFlowNode(Node node){
        String ovsdbName = node.getKey().getNodeId().getValue();
        String ofBridgeName = getOfName(ovsdbName);

        List<NodeConnector> nodeConnectors = new ArrayList<>();
        node.getTerminationPoint()
                .forEach(tp -> nodeConnectors.add(OpenFlowTopologyTestUtils.createNodeConnector(ofBridgeName, tp.getAugmentation(OvsdbTerminationPointAugmentation.class).getOfport(), ovsdbName+"-eth"+tp.getAugmentation(OvsdbTerminationPointAugmentation.class).getOfport())));

        return OpenFlowTopologyTestUtils.createOpenFlowNode(ofBridgeName,nodeConnectors);
    }

    private String getOfName(String ovsdbName){
        String bridgeNumber = ovsdbName.substring(1,ovsdbName.length());
        return "openflow:"+bridgeNumber;
    }

    public static Nodes readOpenFLowTopology(DataBroker dataBroker){
        InstanceIdentifier instanceIdentifier = InstanceIdentifier.builder(Nodes.class).build();
        return (Nodes) MdsalUtils.read(dataBroker,LogicalDatastoreType.CONFIGURATION,instanceIdentifier);
    }

    /**
     * @return List of links between ovswitches
     */
    private static List<Link> getLinkList(){
        List<Link> linkList = new ArrayList<>();

        //openflow nodes
        String of1 = "1";
        String of2 = "2";
        String of3 = "3";
        String of4 = "4";
        String of5 = "5";
        //ports
        Long p1 = 1L;
        Long p2 = 2L;
        Long p3 = 3L;
        Long p4 = 4L;
        Long p5 = 5L;

        //openflow:1
        linkList.add(FlowTopologyTestUtils.createLink(of1,p3,of2,p1));
        linkList.add(FlowTopologyTestUtils.createLink(of1,p5,of4,p1));
        linkList.add(FlowTopologyTestUtils.createLink(of1,p4,of3,p1));
        //openflow:2
        linkList.add(FlowTopologyTestUtils.createLink(of2,p2,of3,p2));
        linkList.add(FlowTopologyTestUtils.createLink(of2,p1,of1,p3));
        //openflow:3
        linkList.add(FlowTopologyTestUtils.createLink(of3,p4,of5,p3));
        linkList.add(FlowTopologyTestUtils.createLink(of3,p1,of1,p4));
        linkList.add(FlowTopologyTestUtils.createLink(of3,p3,of4,p2));
        linkList.add(FlowTopologyTestUtils.createLink(of3,p2,of2,p2));
        //openflow:4
        linkList.add(FlowTopologyTestUtils.createLink(of4,p3,of5,p4));
        linkList.add(FlowTopologyTestUtils.createLink(of4,p2,of3,p3));
        linkList.add(FlowTopologyTestUtils.createLink(of4,p1,of1,p5));
        //openflow:5
        linkList.add(FlowTopologyTestUtils.createLink(of5,p3,of3,p4));
        linkList.add(FlowTopologyTestUtils.createLink(of5,p4,of4,p3));

        return linkList;
    }
}
