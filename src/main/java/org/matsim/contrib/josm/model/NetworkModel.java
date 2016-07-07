package org.matsim.contrib.josm.model;

import javafx.beans.property.ReadOnlyMapProperty;
import javafx.beans.property.ReadOnlyMapWrapper;
import javafx.collections.FXCollections;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.josm.gui.Preferences;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.LinkImpl;
import org.matsim.core.network.NodeImpl;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.data.osm.event.*;
import org.openstreetmap.josm.data.osm.visitor.AbstractVisitor;
import org.openstreetmap.josm.data.osm.visitor.Visitor;
import org.openstreetmap.josm.gui.dialogs.relation.sort.WayConnectionType;
import org.openstreetmap.josm.gui.dialogs.relation.sort.WayConnectionType.Direction;
import org.openstreetmap.josm.gui.dialogs.relation.sort.WayConnectionTypeCalculator;

import java.util.*;

/**
 * Listens to changes in the dataset and their effects on the Network
 *
 *
 */
public class NetworkModel {

	private class NetworkModelDataSetListener implements DataSetListener {

		@Override
		public void dataChanged(DataChangedEvent dataChangedEvent) {
			visitAll();
			fireNotifyDataChanged();
		}

		@Override
		// convert all referred elements of the moved node
		public void nodeMoved(NodeMovedEvent moved) {
			AggregatePrimitives aggregatePrimitivesVisitor = new AggregatePrimitives();
			aggregatePrimitivesVisitor.visit(moved.getNode());
			aggregatePrimitivesVisitor.finished();
			fireNotifyDataChanged();
		}

		@Override
		public void otherDatasetChange(AbstractDatasetChangedEvent arg0) {
		}

		@Override
		// convert added primitive as well as the ones connected to it
		public void primitivesAdded(PrimitivesAddedEvent added) {
			AggregatePrimitives aggregatePrimitivesVisitor = new AggregatePrimitives();
			for (OsmPrimitive primitive : added.getPrimitives()) {
				if (primitive instanceof Way) {
					Way way = (Way) primitive;
					aggregatePrimitivesVisitor.visit(way);
					for (Node node : way.getNodes()) {
						aggregatePrimitivesVisitor.visit(node);
					}
				} else if (primitive instanceof Relation) {
					aggregatePrimitivesVisitor.visit((Relation) primitive);
				} else if (primitive instanceof org.openstreetmap.josm.data.osm.Node) {
					aggregatePrimitivesVisitor.visit((org.openstreetmap.josm.data.osm.Node) primitive);
				}
			}
			aggregatePrimitivesVisitor.finished();
			fireNotifyDataChanged();
		}

		@Override
		// delete any MATSim reference to the removed element and invoke new
		// conversion of referring elements
		public void primitivesRemoved(PrimitivesRemovedEvent primitivesRemoved) {
			AggregatePrimitives aggregatePrimitivesVisitor = new AggregatePrimitives();
			for (OsmPrimitive primitive : primitivesRemoved.getPrimitives()) {
				if (primitive instanceof org.openstreetmap.josm.data.osm.Node) {
					aggregatePrimitivesVisitor.visit(((org.openstreetmap.josm.data.osm.Node) primitive));
				} else if (primitive instanceof Way) {
					aggregatePrimitivesVisitor.visit((Way) primitive);
				} else if (primitive instanceof Relation) {
					aggregatePrimitivesVisitor.visit((Relation) primitive);
				}
			}
			aggregatePrimitivesVisitor.finished();
			fireNotifyDataChanged();
		}

		@Override
		// convert affected relation
		public void relationMembersChanged(RelationMembersChangedEvent arg0) {
			AggregatePrimitives aggregatePrimitivesVisitor = new AggregatePrimitives();
			aggregatePrimitivesVisitor.visit(arg0.getRelation());
			for (OsmPrimitive primitive : arg0.getRelation().getMemberPrimitivesList()) {
				if (primitive instanceof org.openstreetmap.josm.data.osm.Node) {
					aggregatePrimitivesVisitor.visit(((org.openstreetmap.josm.data.osm.Node) primitive));
				} else if (primitive instanceof Way) {
					aggregatePrimitivesVisitor.visit((Way) primitive);
				} else if (primitive instanceof Relation) {
					aggregatePrimitivesVisitor.visit((Relation) primitive);
				}
			}
			aggregatePrimitivesVisitor.finished();
			fireNotifyDataChanged();
		}

		@Override
		// convert affected elements and other connected elements
		public void tagsChanged(TagsChangedEvent changed) {
			AggregatePrimitives aggregatePrimitivesVisitor = new AggregatePrimitives();
			for (OsmPrimitive primitive : changed.getPrimitives()) {
				if (primitive instanceof Way) {
					Way way = (Way) primitive;
					aggregatePrimitivesVisitor.visit(way);
					for (Node node : way.getNodes()) {
						aggregatePrimitivesVisitor.visit(node);
					}
					aggregatePrimitivesVisitor.visit(way);
					List<Link> links = way2Links.get(way);
					if (links != null) {
						for (Link link : links) {
							aggregatePrimitivesVisitor.visit((Node) data.getPrimitiveById(Long.parseLong(link.getFromNode().getId().toString()),
									OsmPrimitiveType.NODE));
							aggregatePrimitivesVisitor.visit((Node) data.getPrimitiveById(Long.parseLong(link.getToNode().getId().toString()),
									OsmPrimitiveType.NODE));
						}
					}
				} else if (primitive instanceof org.openstreetmap.josm.data.osm.Node) {
					aggregatePrimitivesVisitor.visit((org.openstreetmap.josm.data.osm.Node) primitive);
				} else if (primitive instanceof Relation) {
					aggregatePrimitivesVisitor.visit((Relation) primitive);
				}
			}
			aggregatePrimitivesVisitor.finished();
			fireNotifyDataChanged();
		}

		@Override
		// convert affected elements and other connected elements
		public void wayNodesChanged(WayNodesChangedEvent changed) {
			AggregatePrimitives aggregatePrimitivesVisitor = new AggregatePrimitives();
			for (Node node : changed.getChangedWay().getNodes()) {
				aggregatePrimitivesVisitor.visit(node);
			}
			List<Link> links = way2Links.get(changed.getChangedWay());
			if (links != null) {
				for (Link link : links) {
					aggregatePrimitivesVisitor.visit((Node) data.getPrimitiveById(Long.parseLong(link.getFromNode().getId().toString()),
							OsmPrimitiveType.NODE));
					aggregatePrimitivesVisitor.visit((Node) data.getPrimitiveById(Long.parseLong(link.getToNode().getId().toString()),
							OsmPrimitiveType.NODE));
				}
			}
			aggregatePrimitivesVisitor.visit((changed.getChangedWay()));
			aggregatePrimitivesVisitor.finished();
			fireNotifyDataChanged();
		}

	}

	final static String TAG_HIGHWAY = "highway";
	final static String TAG_RAILWAY = "railway";

	private ReadOnlyMapWrapper<Relation, StopArea> stopAreas = new ReadOnlyMapWrapper<>(FXCollections.observableHashMap());
	private ReadOnlyMapWrapper<Relation, Line> lines = new ReadOnlyMapWrapper<>(FXCollections.observableHashMap());
	private ReadOnlyMapWrapper<Relation, Route> routes = new ReadOnlyMapWrapper<>(FXCollections.observableHashMap());

	private final Scenario scenario;

	private final Map<Way, List<Link>> way2Links;
	private final Map<Link, List<WaySegment>> link2Segments;
	private DataSet data;
	private Collection<ScenarioDataChangedListener> listeners = new ArrayList<>();

	public static NetworkModel createNetworkModel(DataSet data) {
		Config config = ConfigUtils.createConfig();
		config.transit().setUseTransit(true);
		return NetworkModel.createNetworkModel(data, ScenarioUtils.createScenario(config), new HashMap<>(), new HashMap<>());
	}

	public static NetworkModel createNetworkModel(DataSet data, Scenario scenario, Map<Way, List<Link>> way2Links, Map<Link, List<WaySegment>> link2Segments) {
		return new NetworkModel(data, Main.pref, scenario, way2Links, link2Segments);
	}

	public interface ScenarioDataChangedListener {
		void notifyDataChanged();
	}

	public void removeListener(ScenarioDataChangedListener listener) {
		listeners.remove(listener);
	}

	void fireNotifyDataChanged() {
		for (ScenarioDataChangedListener listener : listeners) {
			listener.notifyDataChanged();
		}
	}

	public void addListener(ScenarioDataChangedListener listener) {
		listeners.add(listener);
	}

	private NetworkModel(DataSet data, org.openstreetmap.josm.data.Preferences prefs, Scenario scenario, Map<Way, List<Link>> way2Links, Map<Link, List<WaySegment>> link2Segments) {
		this.data = data;
		this.data.addDataSetListener(new NetworkModelDataSetListener());
		prefs.addPreferenceChangeListener(e -> {
			if (e.getKey().equalsIgnoreCase("matsim_keepPaths") || e.getKey().equalsIgnoreCase("matsim_filterActive")
					|| e.getKey().equalsIgnoreCase("matsim_filter_hierarchy") || e.getKey().equalsIgnoreCase("matsim_transit_lite")) {
				visitAll();
			}
			fireNotifyDataChanged();
		});
		Main.addProjectionChangeListener((oldValue, newValue) -> {
			visitAll();
			fireNotifyDataChanged();
		});
		this.scenario = scenario;
		this.way2Links = way2Links;
		this.link2Segments = link2Segments;
	}

	public void visitAll() {
		Convert visitor = new Convert();
		for (Node node : data.getNodes()) {
			visitor.visit(node);
		}
		for (Way way : data.getWays()) {
			visitor.visit(way);
		}
		for (Relation relation : data.getRelations()) {
			visitor.visit(relation);
		}
		fireNotifyDataChanged();
	}

	class AggregatePrimitives implements Visitor {

		Set<OsmPrimitive> primitives = new HashSet<>();

		@Override
		public void visit(Node node) {
			primitives.add(node);
			// When a Node was touched, we need to look at ways (because their
			// length may change)
			// and at relations (because it may be a transit stop)
			// which contain it.

			// Annoyingly, JOSM removes the dataSet property from primitives
			// before calling this listener when
			// a primitive is "hard" deleted (not flagged as deleted).
			// So we have to check for this before asking for its referrers.
			if (node.getDataSet() != null) {
				for (OsmPrimitive primitive : node.getReferrers()) {
					primitive.accept(this);
				}
			}
		}

		@Override
		public void visit(Way way) {
			primitives.add(way);
			// When a Way is touched, we need to look at relations (because they
			// may
			// be transit routes which have changed now).
			// I probably have to look at the nodes (because they may not be
			// needed anymore),
			// but then I would probably traverse the entire net.

			// Annoyingly, JOSM removes the dataSet property from primitives
			// before calling this listener when
			// a primitive is "hard" deleted (not flagged as deleted).
			// So we have to check for this before asking for its referrers.
			if (way.getDataSet() != null) {
				for (OsmPrimitive primitive : way.getReferrers()) {
					primitive.accept(this);
				}
			}
		}

		@Override
		public void visit(Relation relation) {
			if (Preferences.isSupportTransit() && relation.hasTag("type", "route_master")) {
				for (OsmPrimitive osmPrimitive : relation.getMemberPrimitives()) {
					osmPrimitive.accept(this);
				}
			}
			primitives.add(relation);
		}

		@Override
		public void visit(Changeset changeset) {

		}

		void finished() {
			Convert visitor = new Convert();
			for (Node node : OsmPrimitive.getFilteredList(primitives, Node.class)) {
				visitor.visit(node);
			}
			for (Way way : OsmPrimitive.getFilteredList(primitives, Way.class)) {
				visitor.visit(way);
			}
			for (Relation relation : OsmPrimitive.getFilteredList(primitives, Relation.class)) {
				visitor.visit(relation);
			}
		}

	}

	private void searchAndRemoveRoute(Route route) {
		// We do not know what line the route is in, so we have to search for
		// it.
		for (Line line : lines.values()) {
			line.removeRoute(route);
		}
		routes.remove(route.getRelation());
	}

	public Scenario getScenario() {
		return scenario;
	}

	class Convert extends AbstractVisitor {

		final Collection<OsmPrimitive> visited = new HashSet<>();

		void convertWay(Way way) {
			final String wayType = LinkConversionRules.getWayType(way);
			final OsmConvertDefaults.OsmWayDefaults defaults = wayType != null ? OsmConvertDefaults.getWayDefaults().get(wayType) : null;

			final boolean forward = LinkConversionRules.isForward(way, defaults);
			final boolean backward = LinkConversionRules.isBackward(way, defaults);
			final Double freespeed = LinkConversionRules.getFreespeed(way, defaults);
			final Double nofLanesPerDirection = LinkConversionRules.getLanesPerDirection(way, defaults, forward, backward);
			final Double capacity = LinkConversionRules.getCapacity(way, defaults, nofLanesPerDirection);
			final Set<String> modes = LinkConversionRules.getModes(way, defaults);

			final Double taggedLength = LinkConversionRules.getTaggedLength(way);

			if (capacity != null && freespeed != null && nofLanesPerDirection != null && modes != null && (isExplicitelyMatsimTagged(way) || !Preferences.isTransitLite())) {
				List<Node> nodeOrder = new ArrayList<>();
				for (Node current : way.getNodes()) {
					if (scenario.getNetwork().getNodes().containsKey(Id.create(NodeConversionRules.getId(current), org.matsim.api.core.v01.network.Node.class))) {
						nodeOrder.add(current);
					}
				}
				List<Link> links = new ArrayList<>();
				long increment = 0;
				for (int k = 1; k < nodeOrder.size(); k++) {
					List<WaySegment> segs = new ArrayList<>();
					Node nodeFrom = nodeOrder.get(k - 1);
					Node nodeTo = nodeOrder.get(k);
					int fromIdx = way.getNodes().indexOf(nodeFrom);
					int toIdx = way.getNodes().indexOf(nodeTo);
					if (fromIdx > toIdx) { // loop, take latter occurrence
						toIdx = way.getNodes().lastIndexOf(nodeTo);
					}
					Double segmentLength = 0.;
					for (int m = fromIdx; m < toIdx; m++) {
						segs.add(new WaySegment(way, m));
						segmentLength += way.getNode(m).getCoor().greatCircleDistance(way.getNode(m + 1).getCoor());
					}
					if (taggedLength != null) {
						segmentLength = taggedLength * segmentLength / way.getLength();
					}

					// only create link, if both nodes were found, node could be null, since
					// nodes outside a layer were dropped
					Id<org.matsim.api.core.v01.network.Node> fromId = Id.create(NodeConversionRules.getId(nodeFrom), org.matsim.api.core.v01.network.Node.class);
					Id<org.matsim.api.core.v01.network.Node> toId = Id.create(NodeConversionRules.getId(nodeTo), org.matsim.api.core.v01.network.Node.class);
					if (scenario.getNetwork().getNodes().get(fromId) != null && scenario.getNetwork().getNodes().get(toId) != null) {
						if (forward) {
							String id = LinkConversionRules.getId(way, increment, false);
							String origId = LinkConversionRules.getOrigId(way, id, false);
							Link l = scenario.getNetwork().getFactory().createLink(Id.create(id, Link.class), scenario.getNetwork().getNodes().get(fromId), scenario.getNetwork().getNodes().get(toId));
							l.setLength(segmentLength);
							l.setFreespeed(freespeed);
							l.setCapacity(capacity);
							l.setNumberOfLanes(nofLanesPerDirection);
							l.setAllowedModes(modes);
							((LinkImpl) l).setOrigId(origId);
							scenario.getNetwork().addLink(l);
							link2Segments.put(l, segs);
							links.add(l);
						}
						if (backward) {
							String id = LinkConversionRules.getId(way, increment, true);
							String origId = LinkConversionRules.getOrigId(way, id, true);
							Link l = scenario.getNetwork().getFactory().createLink(Id.create(id, Link.class), scenario.getNetwork().getNodes().get(toId), scenario.getNetwork().getNodes().get(fromId));
							l.setLength(segmentLength);
							l.setFreespeed(freespeed);
							l.setCapacity(capacity);
							l.setNumberOfLanes(nofLanesPerDirection);
							l.setAllowedModes(modes);
							((LinkImpl) l).setOrigId(origId);
							scenario.getNetwork().addLink(l);
							link2Segments.put(l, segs);
							links.add(l);
						}
					}
					increment++;
				}
				way2Links.put(way, links);
			}
		}

		private boolean isExplicitelyMatsimTagged(Way way) {
			return way.get(LinkConversionRules.ID) != null;
		}

		private boolean isExplicitelyMatsimTagged(Node node) {
			return node.get(NodeConversionRules.ID) != null;
		}

		private boolean isExplicitelyMatsimTagged(Relation relation) {
			return relation.get("matsim:id") != null;
		}

		@Override
		public void visit(Node node) {
			if (visited.add(node)) {
				scenario.getNetwork().removeNode(Id.create(NodeConversionRules.getId(node), org.matsim.api.core.v01.network.Node.class));
				if (isRelevant(node)) {
					EastNorth eN = Main.getProjection().latlon2eastNorth(node.getCoor());
					NodeImpl matsimNode = (NodeImpl) scenario
							.getNetwork()
							.getFactory()
							.createNode(Id.create(NodeConversionRules.getId(node), org.matsim.api.core.v01.network.Node.class),
									new Coord(eN.getX(), eN.getY()));
					matsimNode.setOrigId(NodeConversionRules.getOrigId(node));
					scenario.getNetwork().addNode(matsimNode);
				}
			}
		}

		private boolean isRelevant(Node node) {
			if (isUsableAndNotRemoved(node) && (isExplicitelyMatsimTagged(node) || !Preferences.isTransitLite())) {
				Way junctionWay = null;
				for (Way way : OsmPrimitive.getFilteredList(node.getReferrers(), Way.class)) {
					if (isUsableAndNotRemoved(way) && LinkConversionRules.isMatsimWay(way)) {
						if (Preferences.isKeepPaths() || way.isFirstLastNode(node) || junctionWay != null || node.hasTag("public_transport", "stop_position")) {
							return true;
						}
						junctionWay = way;
					}
				}
			}
			return false;
		}

		@Override
		public void visit(Way way) {
			if (visited.add(way)) {
				List<Link> oldLinks = way2Links.remove(way);
				if (oldLinks != null) {
					for (Link link : oldLinks) {
						Link removedLink = scenario.getNetwork().removeLink(link.getId());
						link2Segments.remove(removedLink);
					}
				}
				if (isUsableAndNotRemoved(way)) {
					convertWay(way);
				}
			}
		}

		@Override
		public void visit(Relation relation) {
			if (visited.add(relation)) {
				if (Preferences.isSupportTransit()) {
					Route oldRoute = findRoute(relation);
					Route newRoute = createTransitRoute(relation, oldRoute);
					if (oldRoute != null && newRoute == null) {
						oldRoute.setDeleted(true);
					} else if (oldRoute == null && newRoute != null) {
						Line tLine = findOrCreateTransitLine(relation);
						tLine.addRoute(newRoute);
						routes.put(relation, newRoute);
					} else if (oldRoute != null) {
						Line tLine = findOrCreateTransitLine(relation);
						// The line the route is assigned to might have changed,
						// so remove it and add it again.
						searchAndRemoveRoute(oldRoute);
						tLine.addRoute(newRoute);
						routes.put(relation, newRoute);
					}
					stopAreas.remove(relation);
					createTransitStopFacility(relation);
				}
			}
		}

		private Route createTransitRoute(Relation relation, Route oldRoute) {
			if (!relation.isDeleted() && relation.hasTag("type", "route") && relation.get("route") != null) {
				Line line = findOrCreateTransitLine(relation);
				if (line != null) {
					Route newRoute;
					if (oldRoute == null) {
						newRoute = new Route(relation, stopAreas);
					} else {
						// Edit the previous object in place.
						newRoute = oldRoute;
						newRoute.setDeleted(false);
					}
					if (isExplicitelyMatsimTagged(relation) || !Main.pref.getBoolean("matsim_transit_lite")) {
						NetworkRoute networkRoute = determineNetworkRoute(relation);
						newRoute.setRoute(networkRoute);
					}
					return newRoute;
				}
			}
			return null; // not a route
		}

		private void createTransitStopFacility(Relation relation) {
			if (relation.hasTag("type", "public_transport") && relation.hasTag("public_transport", "stop_area")) {
				StopArea stopArea = new StopArea(relation);
				if (stopArea.getCoord() != null) {
					Id<Link> linkId = determineExplicitMatsimLinkId(relation);
					if (linkId != null) {
						stopArea.setLinkId(linkId);
					}
					stopAreas.put(relation, stopArea);
				}
			}
		}

		private Id<Link> determineExplicitMatsimLinkId(Relation relation) {
			for (RelationMember member : relation.getMembers()) {
				if (member.hasRole("matsim:link") && member.isWay()) {
					Way way = member.getWay();
					List<Link> links = way2Links.get(way);
					if (links != null && !links.isEmpty()) {
						return links.get(links.size() - 1).getId();
					}
				}
			}
			return null;
		}

		private NetworkRoute determineNetworkRoute(Relation relation) {
			List<Id<Link>> links = new ArrayList<>();
			List<RelationMember> members = relation.getMembers();
			if (!members.isEmpty()) { // WayConnectionTypeCalculator
				// will crash otherwise
				WayConnectionTypeCalculator calc = new WayConnectionTypeCalculator();
				List<WayConnectionType> connections = calc.updateLinks(members);
				for (int i=0; i<members.size(); i++) {
					RelationMember member = members.get(i);
					if (member.isWay()) {
						Way way = member.getWay();
						List<Link> wayLinks = way2Links.get(way);
						if (wayLinks != null) {
							wayLinks = new ArrayList<>(wayLinks);
							if (connections.get(i).direction.equals(Direction.FORWARD)) {
								for (Link link : wayLinks) {
									if (!link.getId().toString().endsWith("_r")) {
										links.add(link.getId());
									}
								}
							} else if (connections.get(i).direction.equals(Direction.BACKWARD)) {
								// reverse order of links if backwards
								Collections.reverse(wayLinks);
								for (Link link : wayLinks) {
									if (link.getId().toString().endsWith("_r")) {
										links.add(link.getId());
									}
								}
							}
						}
					}
				}
			}
			if (links.isEmpty()) {
				return null;
			} else {
				return RouteUtils.createNetworkRoute(links, scenario.getNetwork());
			}
		}
	}


	// JOSM does not set a primitive to not usable when it is hard-deleted (i.e.
	// not set to deleted).
	// But it sets the dataSet to null when it is hard-deleted, so we
	// additionally check for that.
	private boolean isUsableAndNotRemoved(OsmPrimitive osmPrimitive) {
		return osmPrimitive.isUsable() && osmPrimitive.getDataSet() != null;
	}

	public Route findRoute(OsmPrimitive maybeRelation) {
		if (maybeRelation instanceof Relation) {
			for (Line line : lines.values()) {
				for (Route route : line.getRoutes()) {
					if (route.getRelation() == maybeRelation) {
						return route;
					}
				}
			}
		}
		return null;
	}

	private Line findOrCreateTransitLine(Relation route) {
		for (OsmPrimitive primitive : route.getReferrers()) {
			if (primitive instanceof Relation && primitive.hasTag("type", "route_master")) {
				for (Line line : lines.values()) {
					if (line.getRelation() == primitive) {
						return line;
					}
				}
				Line tLine = new Line((Relation) primitive);
				lines.put(tLine.getRelation(), tLine);
				return tLine;
			}
		}
		return null;
	}

	public Map<Way, List<Link>> getWay2Links() {
		return way2Links;
	}

	public Map<Link, List<WaySegment>> getLink2Segments() {
		return link2Segments;
	}

	public ReadOnlyMapProperty<Relation, StopArea> stopAreas() {
		return stopAreas.getReadOnlyProperty();
	}

	public ReadOnlyMapProperty<Relation, Line> lines() {
		return lines.getReadOnlyProperty();
	}

	public ReadOnlyMapProperty<Relation, Route> routes() {
		return routes.getReadOnlyProperty();
	}

}
