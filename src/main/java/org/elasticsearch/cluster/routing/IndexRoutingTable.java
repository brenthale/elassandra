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

package org.elasticsearch.cluster.routing;

import static com.google.common.collect.Lists.newArrayList;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.gms.Gossiper;
import org.apache.cassandra.service.StorageService;
import org.elasticsearch.ElasticsearchIllegalStateException;
import org.elasticsearch.cassandra.AbstractSearchStrategy;
import org.elasticsearch.cassandra.PrimaryFirstSearchStrategy;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.collect.ImmutableOpenIntMap;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.discovery.DiscoveryService;
import org.elasticsearch.index.shard.ShardId;

import com.carrotsearch.hppc.IntSet;
import com.carrotsearch.hppc.cursors.IntCursor;
import com.carrotsearch.hppc.cursors.IntObjectCursor;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.common.collect.UnmodifiableIterator;

/**
 * The {@link IndexRoutingTable} represents routing information for a single
 * index. The routing table maintains a list of all shards in the index. A
 * single shard in this context has one more instances namely exactly one
 * {@link ShardRouting#primary() primary} and 1 or more replicas. In other
 * words, each instance of a shard is considered a replica while only one
 * replica per shard is a <tt>primary</tt> replica. The <tt>primary</tt> replica
 * can be seen as the "leader" of the shard acting as the primary entry point
 * for operations on a specific shard.
 * <p>
 * Note: The term replica is not directly
 * reflected in the routing table or in releated classes, replicas are
 * represented as {@link ShardRouting}.
 * </p>
 */
public class IndexRoutingTable implements Iterable<IndexShardRoutingTable> {

    private final String index;
    private final ShardShuffler shuffler;

    // note, we assume that when the index routing is created, ShardRoutings are created for all possible number of
    // shards with state set to UNASSIGNED
    private final ImmutableOpenIntMap<IndexShardRoutingTable> shards;

    private final ImmutableList<ShardRouting> allShards;
    private final ImmutableList<ShardRouting> allActiveShards;

    IndexRoutingTable(String index, ImmutableOpenIntMap<IndexShardRoutingTable> shards) {
        this.index = index;
        this.shuffler = new RotationShardShuffler(ThreadLocalRandom.current().nextInt());
        this.shards = shards;
        ImmutableList.Builder<ShardRouting> allShards = ImmutableList.builder();
        ImmutableList.Builder<ShardRouting> allActiveShards = ImmutableList.builder();
        for (IntObjectCursor<IndexShardRoutingTable> cursor : shards) {
            for (ShardRouting shardRouting : cursor.value) {
                allShards.add(shardRouting);
                if (shardRouting.active()) {
                    allActiveShards.add(shardRouting);
                }
            }
        }
        this.allShards = allShards.build();
        this.allActiveShards = allActiveShards.build();
    }

    /**
     * Build the local per index routing table.
     * One local primary ShardRouting (index 0) + X remote primary shard for alive nodes, each ShardRouting with an allocated set of token ranges (geen status).
     * If some range are missing, add one unassigned primary shard with orphan ranges to reflect partial unavailability with CL=1 (red status).
     * If N node are dead, add N unassigned replica shards with empty ranges to reflect partial unavailability with no impact (orange status)  
     * @param localPrimaryShardRouting
     * @param clusterService
     * @param currentState
     */
    IndexRoutingTable(ShardRouting localPrimaryShardRouting, ClusterService clusterService, ClusterState currentState) {
        this.index = localPrimaryShardRouting.index();
        this.shuffler = new RotationShardShuffler(ThreadLocalRandom.current().nextInt());
        ImmutableList.Builder<ShardRouting> allShards = ImmutableList.builder();
        ImmutableList.Builder<ShardRouting> allActiveShards = ImmutableList.builder();
        ImmutableOpenIntMap.Builder<IndexShardRoutingTable> shards = ImmutableOpenIntMap.builder();

        AbstractSearchStrategy.Result topologyResult = null;
        try {
            Set<InetAddress> aliveEndpoints = Gossiper.instance.getLiveMembers();
            Set<InetAddress> unreachableEndpoints = Gossiper.instance.getUnreachableMembers();
            topologyResult = new PrimaryFirstSearchStrategy().topology(this.index);

            DiscoveryNode localNode = currentState.nodes().getLocalNode();

            //ImmutableShardRouting(String index, int shardId,  String currentNodeId, boolean isPrimary, ShardRoutingState state, long version, Collection<Range<Token>> tokenRanges)
            ShardRouting shardRouting = new ImmutableShardRouting(index, 0, localNode.id(), true, localPrimaryShardRouting.state(), localPrimaryShardRouting.version(), topologyResult.getTokenRanges(localNode
                    .getInetAddress()));
            allShards.add(shardRouting);
            allActiveShards.add(shardRouting);

            int i = 0;
            List<ShardRouting> shardRoutingList = new ArrayList<ShardRouting>();
            shardRoutingList.add(shardRouting);
            if (unreachableEndpoints.size() > 0) {
                // add unassigned secondary routingShard (yellow status)
                for (InetAddress deadNode : unreachableEndpoints) {
                    for (List<InetAddress> endPoints : topologyResult.getRangeToAddressMap().values()) {
                        if (endPoints.contains(deadNode) && endPoints.contains(localNode.getInetAddress())) {
                            shardRoutingList
                                    .add(new ImmutableShardRouting(index, i, currentState.nodes().findByInetAddress(deadNode).id(), false, ShardRoutingState.UNASSIGNED, currentState.version(), AbstractSearchStrategy.EMPTY_RANGE_TOKEN));
                            break;
                        }
                    }
                }
            }
            shards.put(0, new IndexShardRoutingTable(new ShardId(index, 0), ImmutableList.copyOf(shardRoutingList), (localPrimaryShardRouting.state() == ShardRoutingState.INITIALIZING)));
            i++; // 0=local shard, i > 0 for remote shards

            // add one primary ShardRouting for remote alive nodes and some unassigned replica shards for DEAD node. 
            for (DiscoveryNode node : currentState.nodes()) {
                if (!localNode.id().equals(node.id())) {
                    Collection<Range<Token>> token_ranges = topologyResult.getTokenRanges(node.getInetAddress());
                    if (token_ranges != null) {
                        // node is alive for some token range
                        ShardRoutingState shardRoutingState = ShardRoutingState.INITIALIZING;

                        // get state from current cluster state
                        RoutingNode routingNode = currentState.getRoutingNodes().node(node.id());
                        if ((routingNode != null) && (routingNode.size() > 0)) {
                            shardRoutingState = routingNode.get(0).state();
                        }

                        // get state from gossip state
                        shardRoutingState = clusterService.readIndexShardState(node.getInetAddress(), index, shardRoutingState);

                        shardRouting = new ImmutableShardRouting(index, i, node.id(), true, shardRoutingState, // We assue shard is started on alive nodes...
                        currentState.version(), token_ranges);
                        allShards.add(shardRouting);
                        if (shardRouting.active()) {
                            allActiveShards.add(shardRouting);
                        }

                        shardRoutingList = new ArrayList<ShardRouting>();
                        shardRoutingList.add(shardRouting);
                        if (unreachableEndpoints.size() > 0) {
                            // add unassigned secondary routingShard (yellow status)
                            for (InetAddress deadNode : unreachableEndpoints) {
                                for (List<InetAddress> endPoints : topologyResult.getRangeToAddressMap().values()) {
                                    if (endPoints.contains(deadNode) && endPoints.contains(node.getInetAddress())) {
                                        shardRoutingList
                                                .add(new ImmutableShardRouting(index, i, currentState.nodes().findByInetAddress(deadNode).id(), false, ShardRoutingState.UNASSIGNED, currentState
                                                        .version(), AbstractSearchStrategy.EMPTY_RANGE_TOKEN));
                                        break;
                                    }
                                }
                            }
                        }
                        shards.put(i, new IndexShardRoutingTable(new ShardId(index, i), ImmutableList.copyOf(shardRoutingList), false));
                        i++;
                    }
                }
            }

            if (!topologyResult.isConsistent()) {
                // add a unassigned primary IndexShardRoutingTable to reflect missing data (red status).
                shardRouting = new ImmutableShardRouting(index, i, currentState.nodes().findByInetAddress(unreachableEndpoints.iterator().next()).id(), true, ShardRoutingState.UNASSIGNED, currentState
                        .version(), topologyResult.getOrphanTokenRanges());
                shards.put(i, new IndexShardRoutingTable(new ShardId(index, i), ImmutableList.of(shardRouting), false));
            }

        } catch (java.lang.AssertionError e) {
            // thrown by cassandra when the keyspace is not yet create locally. 
            // We must wait for a gossip schema change to update the routing Table.
            Loggers.getLogger(getClass()).warn("Keyspace {} not yet available", e, this.index);
        }

        this.shards = shards.build();
        this.allShards = allShards.build();
        this.allActiveShards = allActiveShards.build();
    }

    /**
     * Return the index id
     *
     * @return id of the index
     */
    public String index() {
        return this.index;
    }

    /*
     * Return the primary ShardRouting hosted on nodeId.
     * (There is no more replica shards in elasticsearch, so you can't have more than one shardRouting per node for an index.
     */
    public ShardRouting getShardRouting(String nodeId) {
        List<ShardRouting> list = new ArrayList<ShardRouting>(1);
        for (ShardRouting sr : allShards) {
            if (sr.currentNodeId().equals(nodeId)) {
                return sr;
            }
        }
        return null;
    }

    /**
     * Return the index id
     *
     * @return id of the index
     */
    public String getIndex() {
        return index();
    }

    /**
     * creates a new {@link IndexRoutingTable} with all shard versions normalized
     *
     * @return new {@link IndexRoutingTable}
     */
    public IndexRoutingTable normalizeVersions() {
        IndexRoutingTable.Builder builder = new Builder(this.index);
        for (IntObjectCursor<IndexShardRoutingTable> cursor : shards) {
            builder.addIndexShard(cursor.value.normalizeVersions());
        }
        return builder.build();
    }

    public void validate(RoutingTableValidation validation, MetaData metaData) {
        if (!metaData.hasIndex(index())) {
            validation.addIndexFailure(index(), "Exists in routing does not exists in metadata");
            return;
        }
        IndexMetaData indexMetaData = metaData.index(index());
        for (String failure : validate(indexMetaData)) {
            validation.addIndexFailure(index, failure);
        }

    }

    /**
     * validate based on a meta data, returning failures found
     */
    public List<String> validate(IndexMetaData indexMetaData) {
        ArrayList<String> failures = new ArrayList<>();

        // check the number of shards = available nodes
        /*
        if (indexMetaData.numberOfShards() != shards().size()) {
            Set<Integer> expected = Sets.newHashSet();
            for (int i = 0; i < indexMetaData.numberOfShards(); i++) {
                expected.add(i);
            }
            for (IndexShardRoutingTable indexShardRoutingTable : this) {
                expected.remove(indexShardRoutingTable.shardId().id());
            }
            failures.add("Wrong number of shards in routing table, missing: " + expected);
        }
        // check the replicas = 
        for (IndexShardRoutingTable indexShardRoutingTable : this) {
            int routingNumberOfReplicas = indexShardRoutingTable.size() - 1;
            if (routingNumberOfReplicas != indexMetaData.numberOfReplicas()) {
                failures.add("Shard [" + indexShardRoutingTable.shardId().id()
                        + "] routing table has wrong number of replicas, expected [" + indexMetaData.numberOfReplicas() + "], got [" + routingNumberOfReplicas + "]");
            }
            for (ShardRouting shardRouting : indexShardRoutingTable) {
                if (!shardRouting.index().equals(index())) {
                    failures.add("shard routing has an index [" + shardRouting.index() + "] that is different than the routing table");
                }
            }
        }
        */
        return failures;
    }

    @Override
    public UnmodifiableIterator<IndexShardRoutingTable> iterator() {
        return shards.valuesIt();
    }

    /**
     * Calculates the number of nodes that hold one or more shards of this index
     * {@link IndexRoutingTable} excluding the nodes with the node ids give as
     * the <code>excludedNodes</code> parameter.
     *
     * @param excludedNodes id of nodes that will be excluded
     * @return number of distinct nodes this index has at least one shard allocated on
     */
    public int numberOfNodesShardsAreAllocatedOn(String... excludedNodes) {
        Set<String> nodes = Sets.newHashSet();
        for (IndexShardRoutingTable shardRoutingTable : this) {
            for (ShardRouting shardRouting : shardRoutingTable) {
                if (shardRouting.assignedToNode()) {
                    String currentNodeId = shardRouting.currentNodeId();
                    boolean excluded = false;
                    if (excludedNodes != null) {
                        for (String excludedNode : excludedNodes) {
                            if (currentNodeId.equals(excludedNode)) {
                                excluded = true;
                                break;
                            }
                        }
                    }
                    if (!excluded) {
                        nodes.add(currentNodeId);
                    }
                }
            }
        }
        return nodes.size();
    }

    public ImmutableOpenIntMap<IndexShardRoutingTable> shards() {
        return shards;
    }

    public ImmutableOpenIntMap<IndexShardRoutingTable> getShards() {
        return shards();
    }

    public IndexShardRoutingTable shard(int shardId) {
        return shards.get(shardId);
    }

    /**
     * Returns <code>true</code> if all shards are primary and active. Otherwise <code>false</code>.
     */
    public boolean allPrimaryShardsActive() {
        return primaryShardsActive() == shards().size();
    }

    /**
     * Calculates the number of primary shards in active state in routing table
     *
     * @return number of active primary shards
     */
    public int primaryShardsActive() {
        int counter = 0;
        for (IndexShardRoutingTable shardRoutingTable : this) {
            if (shardRoutingTable.primaryShard().active()) {
                counter++;
            }
        }
        return counter;
    }

    /**
     * Returns <code>true</code> if all primary shards are in
     * {@link ShardRoutingState#UNASSIGNED} state. Otherwise <code>false</code>.
     */
    public boolean allPrimaryShardsUnassigned() {
        return primaryShardsUnassigned() == shards.size();
    }

    /**
     * Calculates the number of primary shards in the routing table the are in
     * {@link ShardRoutingState#UNASSIGNED} state.
     */
    public int primaryShardsUnassigned() {
        int counter = 0;
        for (IndexShardRoutingTable shardRoutingTable : this) {
            if (shardRoutingTable.primaryShard().unassigned()) {
                counter++;
            }
        }
        return counter;
    }

    /**
     * Returns a {@link List} of shards that match one of the states listed in {@link ShardRoutingState states}
     *
     * @param state {@link ShardRoutingState} to retrieve
     * @return a {@link List} of shards that match one of the given {@link ShardRoutingState states}
     */
    public List<ShardRouting> shardsWithState(ShardRoutingState state) {
        List<ShardRouting> shards = newArrayList();
        for (IndexShardRoutingTable shardRoutingTable : this) {
            shards.addAll(shardRoutingTable.shardsWithState(state));
        }
        return shards;
    }

    /**
     * Returns an unordered iterator over all shards (including replicas).
     */
    public ShardsIterator randomAllShardsIt() {
        return new PlainShardsIterator(shuffler.shuffle(allShards));
    }

    /**
     * Returns an unordered iterator over all active shards (including replicas).
     */
    public ShardsIterator randomAllActiveShardsIt() {
        return new PlainShardsIterator(shuffler.shuffle(allActiveShards));
    }

    /**
     * A group shards iterator where each group ({@link ShardIterator}
     * is an iterator across shard replication group.
     */
    public GroupShardsIterator groupByShardsIt() {
        // use list here since we need to maintain identity across shards
        ArrayList<ShardIterator> set = new ArrayList<>(shards.size());
        for (IndexShardRoutingTable indexShard : this) {
            set.add(indexShard.shardsIt());
        }
        return new GroupShardsIterator(set);
    }

    /**
     * A groups shards iterator where each groups is a single {@link ShardRouting} and a group
     * is created for each shard routing.
     * <p/>
     * <p>This basically means that components that use the {@link GroupShardsIterator} will iterate
     * over *all* the shards (all the replicas) within the index.</p>
     */
    public GroupShardsIterator groupByAllIt() {
        // use list here since we need to maintain identity across shards
        ArrayList<ShardIterator> set = new ArrayList<>();
        for (IndexShardRoutingTable indexShard : this) {
            for (ShardRouting shardRouting : indexShard) {
                set.add(shardRouting.shardsIt());
            }
        }
        return new GroupShardsIterator(set);
    }

    public void validate() throws RoutingValidationException {
    }

    public static Builder builder(String index) {
        return new Builder(index);
    }

    public static class Builder {

        private final String index;
        private final ImmutableOpenIntMap.Builder<IndexShardRoutingTable> shards = ImmutableOpenIntMap.builder();

        public Builder(String index) {
            this.index = index;
        }

        /**
         * Reads an {@link IndexRoutingTable} from an {@link StreamInput}
         *
         * @param in {@link StreamInput} to read the {@link IndexRoutingTable} from
         * @return {@link IndexRoutingTable} read
         * @throws IOException if something happens during read
         */
        public static IndexRoutingTable readFrom(StreamInput in) throws IOException {
            String index = in.readString();
            Builder builder = new Builder(index);

            int size = in.readVInt();
            for (int i = 0; i < size; i++) {
                builder.addIndexShard(IndexShardRoutingTable.Builder.readFromThin(in, index));
            }

            return builder.build();
        }

        /**
         * Writes an {@link IndexRoutingTable} to a {@link StreamOutput}.
         *
         * @param index {@link IndexRoutingTable} to write
         * @param out   {@link StreamOutput} to write to
         * @throws IOException if something happens during write
         */
        public static void writeTo(IndexRoutingTable index, StreamOutput out) throws IOException {
            out.writeString(index.index());
            out.writeVInt(index.shards.size());
            for (IndexShardRoutingTable indexShard : index) {
                IndexShardRoutingTable.Builder.writeToThin(indexShard, out);
            }
        }

        /**
         * Initializes a new empty index, as if it was created from an API.
         */
        public Builder initializeAsNew(IndexMetaData indexMetaData) {
            return initializeEmpty(indexMetaData, true);
        }

        /**
         * Initializes a new empty index, as if it was created from an API.
         */
        public Builder initializeAsRecovery(IndexMetaData indexMetaData) {
            return initializeEmpty(indexMetaData, false);
        }

        /**
         * Initializes a new empty index, to be restored from a snapshot
         */
        public Builder initializeAsNewRestore(IndexMetaData indexMetaData, RestoreSource restoreSource, IntSet ignoreShards) {
            return initializeAsRestore(indexMetaData, restoreSource, ignoreShards, true);
        }

        /**
         * Initializes an existing index, to be restored from a snapshot
         */
        public Builder initializeAsRestore(IndexMetaData indexMetaData, RestoreSource restoreSource) {
            return initializeAsRestore(indexMetaData, restoreSource, null, false);
        }

        /**
         * Initializes an index, to be restored from snapshot
         */
        private Builder initializeAsRestore(IndexMetaData indexMetaData, RestoreSource restoreSource, IntSet ignoreShards, boolean asNew) {
            if (!shards.isEmpty()) {
                throw new ElasticsearchIllegalStateException("trying to initialize an index with fresh shards, but already has shards created");
            }
            for (int shardId = 0; shardId < indexMetaData.numberOfShards(); shardId++) {
                IndexShardRoutingTable.Builder indexShardRoutingBuilder = new IndexShardRoutingTable.Builder(new ShardId(indexMetaData.index(), shardId), asNew ? false : true);
                for (int i = 0; i <= indexMetaData.numberOfReplicas(); i++) {
                    if (asNew && ignoreShards.contains(shardId)) {
                        // This shards wasn't completely snapshotted - restore it as new shard
                        indexShardRoutingBuilder.addShard(new ImmutableShardRouting(index, shardId, null, i == 0, ShardRoutingState.UNASSIGNED, 0));
                    } else {
                        indexShardRoutingBuilder.addShard(new ImmutableShardRouting(index, shardId, null, null, i == 0 ? restoreSource : null, i == 0, ShardRoutingState.UNASSIGNED, 0));
                    }
                }
                shards.put(shardId, indexShardRoutingBuilder.build());
            }
            return this;
        }

        /**
         * Initializes a new empty index, with an option to control if its from an API or not.
         */
        private Builder initializeEmpty(IndexMetaData indexMetaData, boolean asNew) {
            if (!shards.isEmpty()) {
                throw new ElasticsearchIllegalStateException("trying to initialize an index with fresh shards, but already has shards created");
            }
            for (int shardId = 0; shardId < indexMetaData.numberOfShards(); shardId++) {
                IndexShardRoutingTable.Builder indexShardRoutingBuilder = new IndexShardRoutingTable.Builder(new ShardId(indexMetaData.index(), shardId), asNew ? false : true);
                for (int i = 0; i <= indexMetaData.numberOfReplicas(); i++) {
                    indexShardRoutingBuilder.addShard(new ImmutableShardRouting(index, shardId, null, i == 0, ShardRoutingState.UNASSIGNED, 0));
                }
                shards.put(shardId, indexShardRoutingBuilder.build());
            }
            return this;
        }

        public Builder addReplica() {
            for (IntCursor cursor : shards.keys()) {
                int shardId = cursor.value;
                // version 0, will get updated when reroute will happen
                ImmutableShardRouting shard = new ImmutableShardRouting(index, shardId, null, false, ShardRoutingState.UNASSIGNED, 0);
                shards.put(shardId, new IndexShardRoutingTable.Builder(shards.get(shard.id())).addShard(shard).build());
            }
            return this;
        }

        public Builder removeReplica() {
            for (IntCursor cursor : shards.keys()) {
                int shardId = cursor.value;
                IndexShardRoutingTable indexShard = shards.get(shardId);
                if (indexShard.replicaShards().isEmpty()) {
                    // nothing to do here!
                    return this;
                }
                // re-add all the current ones
                IndexShardRoutingTable.Builder builder = new IndexShardRoutingTable.Builder(indexShard.shardId(), indexShard.primaryAllocatedPostApi());
                for (ShardRouting shardRouting : indexShard) {
                    builder.addShard(new ImmutableShardRouting(shardRouting));
                }
                // first check if there is one that is not assigned to a node, and remove it
                boolean removed = false;
                for (ShardRouting shardRouting : indexShard) {
                    if (!shardRouting.primary() && !shardRouting.assignedToNode()) {
                        builder.removeShard(shardRouting);
                        removed = true;
                        break;
                    }
                }
                if (!removed) {
                    for (ShardRouting shardRouting : indexShard) {
                        if (!shardRouting.primary()) {
                            builder.removeShard(shardRouting);
                            removed = true;
                            break;
                        }
                    }
                }
                shards.put(shardId, builder.build());
            }
            return this;
        }

        public Builder addIndexShard(IndexShardRoutingTable indexShard) {
            shards.put(indexShard.shardId().id(), indexShard);
            return this;
        }

        /**
         * Clears the post allocation flag for the specified shard
         */
        public Builder clearPostAllocationFlag(ShardId shardId) {
            assert this.index.equals(shardId.index().name());
            IndexShardRoutingTable indexShard = shards.get(shardId.id());
            shards.put(indexShard.shardId().id(), new IndexShardRoutingTable(indexShard.shardId(), indexShard.shards(), false));
            return this;
        }

        /**
         * Adds a new shard routing (makes a copy of it), with reference data used from the index shard routing table
         * if it needs to be created.
         */
        public Builder addShard(IndexShardRoutingTable refData, ShardRouting shard) {
            IndexShardRoutingTable indexShard = shards.get(shard.id());
            if (indexShard == null) {
                indexShard = new IndexShardRoutingTable.Builder(refData.shardId(), refData.primaryAllocatedPostApi()).addShard(new ImmutableShardRouting(shard)).build();
            } else {
                indexShard = new IndexShardRoutingTable.Builder(indexShard).addShard(new ImmutableShardRouting(shard)).build();
            }
            shards.put(indexShard.shardId().id(), indexShard);
            return this;
        }

        public IndexRoutingTable build() throws RoutingValidationException {
            IndexRoutingTable indexRoutingTable = new IndexRoutingTable(index, shards.build());
            indexRoutingTable.validate();
            return indexRoutingTable;
        }
    }

    public String prettyPrint() {
        StringBuilder sb = new StringBuilder("-- index [" + index + "]\n");
        for (IndexShardRoutingTable indexShard : this) {
            sb.append("----shard_id [").append(indexShard.shardId().index().name()).append("][").append(indexShard.shardId().id()).append("]\n");
            for (ShardRouting shard : indexShard) {
                sb.append("--------").append(shard.shortSummary()).append("\n");
            }
        }
        return sb.toString();
    }

}
