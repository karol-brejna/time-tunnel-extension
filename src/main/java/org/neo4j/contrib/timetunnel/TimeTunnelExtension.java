package org.neo4j.contrib.timetunnel;

import org.joda.time.Interval;
import org.joda.time.ReadableInterval;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.traversal.*;

import javax.ws.rs.*;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.*;

@Path("/")
public class TimeTunnelExtension {

    @Context
    GraphDatabaseService graphDatabaseService;

    @GET
    @Path("{startLabel}/{startProperty}/{startValue}")
    @Produces(MediaType.APPLICATION_JSON)
    public Collection<Map<String, Object>> findPathsWithTimeTunnel(
            @PathParam("startLabel") String startLabel,
            @PathParam("startProperty") String startProperty,
            @PathParam("startValue") String startValue,
            @QueryParam("reltype") List<String> reltypes,
            @QueryParam("prop") List<String> returnProps,
            @QueryParam("fromProp") @DefaultValue("dateFrom") String fromPropertyName,
            @QueryParam("toProp") @DefaultValue("dateTo") String toPropertyName,
            @QueryParam("datePattern") @DefaultValue("yyyy-MM-dd HH:mm:ss.SSSS") String datePattern
            )
    {
        try (Transaction tx = graphDatabaseService.beginTx()) {

            // configure expander with relationship types
            TimeTunnelPathExpander timeTunnelPathExpander = new TimeTunnelPathExpander(fromPropertyName, toPropertyName, datePattern);
            for (String reltype : reltypes) {
                timeTunnelPathExpander.add(DynamicRelationshipType.withName(reltype));
            }

            TraversalDescription timeTunnelTraversal = graphDatabaseService.traversalDescription()
                    .expand(timeTunnelPathExpander, new InitialBranchState.State<ReadableInterval>(
                            new Interval(0, Long.MAX_VALUE), null
                    ))
                    .evaluator(new HasOverlapPathEvaluator())       // add all nodes to resultset having time tunnel
                    .evaluator(Evaluators.excludeStartPosition());  // exclude start node

            ResourceIterable<Node> startNodes = graphDatabaseService.findNodesByLabelAndProperty(
                    DynamicLabel.label(startLabel), startProperty, startValue
            );

            // consume traversal and build return data structure
            Collection<Map<String,Object>> result = new ArrayList<>();
            for (org.neo4j.graphdb.Path p : timeTunnelTraversal.traverse(startNodes)) {
                TraversalBranch path = (TraversalBranch)p; // need to cast here to get access to state variable

                Map<String,Object> map = new HashMap<>();
                result.add(map);
                map.put("labels", getLabels(path.endNode()));

                for (String key : returnProps) {
                    map.put(key, path.endNode().getProperty(key, null));
                }
                ReadableInterval interval = (ReadableInterval) path.state();
                map.put(fromPropertyName, interval.getStart().toDateTime().toString(datePattern));
                map.put(toPropertyName, interval.getEnd().toDateTime().toString(datePattern));
            }
            return result;
        }
    }

    private List<String> getLabels(Node node) {
        List<String> result = new ArrayList<>();
        for (Label label : node.getLabels()) {
            result.add(label.name());
        }
        return result;
    }
}