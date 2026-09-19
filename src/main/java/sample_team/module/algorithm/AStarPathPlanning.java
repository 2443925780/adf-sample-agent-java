package sample_team.module.algorithm;

import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.algorithm.PathPlanning;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import rescuecore2.misc.collections.LazyMap;
import rescuecore2.standard.entities.Area;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;

public class AStarPathPlanning extends PathPlanning {

  private Map<EntityID, Set<EntityID>> graph;

  private EntityID from;
  private Collection<EntityID> targets;
  private List<EntityID> result;

  public AStarPathPlanning(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    this.init();
  }


  private void init() {
    Map<EntityID,
        Set<EntityID>> neighbours = new LazyMap<EntityID, Set<EntityID>>() {

          @Override
          public Set<EntityID> createValue() {
            return new HashSet<>();
          }
        };
    for (Entity next : this.worldInfo) {
      if (next instanceof Area) {
        Collection<EntityID> areaNeighbours = ((Area) next).getNeighbours();
        if (areaNeighbours != null) {
          neighbours.get(next.getID()).addAll(areaNeighbours);
        }
      }
    }
    this.graph = neighbours;
  }


  @Override
  public List<EntityID> getResult() {
    return this.result;
  }


  @Override
  public PathPlanning setFrom(EntityID id) {
    this.from = id;
    return this;
  }


  @Override
  public PathPlanning setDestination(Collection<EntityID> targets) {
    this.targets = targets;
    return this;
  }


  @Override
  public PathPlanning precompute(PrecomputeData precomputeData) {
    super.precompute(precomputeData);
    return this;
  }


  @Override
  public PathPlanning resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    return this;
  }


  @Override
  public PathPlanning preparate() {
    super.preparate();
    return this;
  }


  @Override
  public PathPlanning calc() {
    this.result = null;
    if (this.from == null || this.targets == null || this.targets.isEmpty()) {
      return this;
    }

    Queue<Node> open = new PriorityQueue<>(
        Comparator.comparingDouble(Node::estimate));
    Map<EntityID, Node> nodeMap = new HashMap<>();
    Set<EntityID> closed = new HashSet<>();

    Node start = new Node(null, this.from, 0.0);
    open.add(start);
    nodeMap.put(this.from, start);

    while (!open.isEmpty()) {
      Node current = open.poll();
      if (nodeMap.get(current.getID()) != current
          || closed.contains(current.getID())) {
        continue;
      }

      if (this.targets.contains(current.getID())) {
        this.result = this.buildPath(current);
        return this;
      }

      closed.add(current.getID());
      Set<EntityID> neighbours = this.graph.get(current.getID());
      if (neighbours == null) {
        continue;
      }

      for (EntityID neighbour : neighbours) {
        if (neighbour == null || closed.contains(neighbour)) {
          continue;
        }

        double cost = current.getCost()
            + this.worldInfo.getDistance(current.getID(), neighbour);
        Node known = nodeMap.get(neighbour);
        if (known == null || cost < known.getCost()) {
          Node next = new Node(current, neighbour, cost);
          nodeMap.put(neighbour, next);
          open.add(next);
        }
      }
    }

    return this;
  }


  private List<EntityID> buildPath(Node node) {
    LinkedList<EntityID> path = new LinkedList<>();
    while (node != null) {
      path.addFirst(node.getID());
      node = node.getParent();
    }
    return path;
  }


  private class Node {

    private final EntityID id;
    private final Node parent;
    private final double cost;
    private final double heuristic;

    Node(Node parent, EntityID id, double cost) {
      this.id = id;
      this.parent = parent;
      this.cost = cost;
      this.heuristic = AStarPathPlanning.this.getHeuristic(id);
    }


    EntityID getID() {
      return this.id;
    }


    Node getParent() {
      return this.parent;
    }


    double getCost() {
      return this.cost;
    }


    double estimate() {
      return this.cost + this.heuristic;
    }
  }


  private double getHeuristic(EntityID id) {
    double nearest = Double.POSITIVE_INFINITY;
    for (EntityID target : this.targets) {
      if (target != null) {
        nearest = Math.min(nearest,
            this.worldInfo.getDistance(id, target));
      }
    }
    return nearest;
  }
}
