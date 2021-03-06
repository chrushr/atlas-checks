package org.openstreetmap.atlas.checks.validation.intersections;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.openstreetmap.atlas.checks.base.BaseCheck;
import org.openstreetmap.atlas.checks.flag.CheckFlag;
import org.openstreetmap.atlas.geography.Location;
import org.openstreetmap.atlas.geography.atlas.items.Area;
import org.openstreetmap.atlas.geography.atlas.items.AtlasObject;
import org.openstreetmap.atlas.geography.atlas.items.Edge;
import org.openstreetmap.atlas.geography.atlas.items.Node;
import org.openstreetmap.atlas.tags.AmenityTag;
import org.openstreetmap.atlas.tags.AreaTag;
import org.openstreetmap.atlas.tags.BuildingTag;
import org.openstreetmap.atlas.tags.CoveredTag;
import org.openstreetmap.atlas.tags.EntranceTag;
import org.openstreetmap.atlas.tags.HighwayTag;
import org.openstreetmap.atlas.tags.LayerTag;
import org.openstreetmap.atlas.tags.ServiceTag;
import org.openstreetmap.atlas.tags.TunnelTag;
import org.openstreetmap.atlas.tags.annotations.validation.Validators;
import org.openstreetmap.atlas.utilities.collections.Iterables;
import org.openstreetmap.atlas.utilities.configuration.Configuration;

/**
 * Flags buildings that intersect/touch centerlines of roads. This doesn't address cases where
 * buildings get really close to roads, but don't overlap them.
 *
 * @author mgostintsev
 */
public class BuildingRoadIntersectionCheck extends BaseCheck<Long>
{
    private static final List<String> FALLBACK_INSTRUCTIONS = Arrays
            .asList("Building (id-{0,number,#}) intersects road (id-{1,number,#})");
    private static final String INDOOR_KEY = "indoor";
    private static final String YES_VALUE = "yes";
    private static final long serialVersionUID = 5986017212661374165L;

    private static Predicate<Edge> ignoreTags()
    {
        return edge -> !(Validators.isOfType(edge, CoveredTag.class, CoveredTag.YES)
                || Validators.isOfType(edge, TunnelTag.class, TunnelTag.BUILDING_PASSAGE,
                        TunnelTag.YES)
                || Validators.isOfType(edge, AreaTag.class, AreaTag.YES)
                || YES_VALUE.equals(edge.tag(INDOOR_KEY))
                || Validators.isOfType(edge, HighwayTag.class, HighwayTag.SERVICE)
                        && Validators.isOfType(edge, ServiceTag.class, ServiceTag.DRIVEWAY)
                || edge.connectedNodes().stream().anyMatch(
                        node -> Validators.isOfType(node, EntranceTag.class, EntranceTag.YES)
                                || Validators.isOfType(node, AmenityTag.class,
                                        AmenityTag.PARKING_ENTRANCE)));
    }

    private static Predicate<Edge> intersectsCoreWayInvalidly(final Area building)
    {
        // An invalid intersection is determined by checking that the highway is a core way
        return edge -> HighwayTag.isCoreWay(edge)
                // And if the edge intersects the building polygon
                && edge.asPolyLine().intersects(building.asPolygon())
                // And ignore intersections where edge has highway=service and building has
                // Amenity=fuel
                && !(Validators.isOfType(edge, HighwayTag.class, HighwayTag.SERVICE)
                        && Validators.isOfType(building, AmenityTag.class, AmenityTag.FUEL))
                // And if the layers have the same layer value
                && LayerTag.getTaggedValue(edge).orElse(0L)
                        .equals(LayerTag.getTaggedValue(building).orElse(0L))
                // And if the building/edge intersection is not valid
                && !isValidIntersection(building, edge);
    }

    /**
     * An edge intersecting with a building that doesn't have the proper tags is only valid if it
     * intersects at one single node and that node is shared with an edge that has the proper tags
     * and it is not enclosed in the building
     *
     * @param building
     *            the building being processed
     * @param edge
     *            the edge being examined
     * @return true if the intersection is valid, false otherwise
     */
    private static boolean isValidIntersection(final Area building, final Edge edge)
    {
        final Node edgeStart = edge.start();
        final Node edgeEnd = edge.end();
        final Set<Location> intersections = building.asPolygon().intersections(edge.asPolyLine());
        return intersections.size() == 1
                && !building.asPolygon().fullyGeometricallyEncloses(edge.asPolyLine())
                && (intersections.contains(edgeStart.getLocation())
                        || intersections.contains(edgeEnd.getLocation()));
    }

    /**
     * Default constructor
     *
     * @param configuration
     *            the JSON configuration for this check
     */
    public BuildingRoadIntersectionCheck(final Configuration configuration)
    {
        super(configuration);
    }

    @Override
    public boolean validCheckForObject(final AtlasObject object)
    {
        // We could go about this a couple of ways. Either check all buildings, all roads, or both.
        // Since intersections will be flagged for any feature, it makes sense to loop over the
        // smallest of the three sets - buildings (for most countries). This may change over time.
        return object instanceof Area && BuildingTag.isBuilding(object)
                && !HighwayTag.isHighwayArea(object)
                && !Validators.isOfType(object, AmenityTag.class, AmenityTag.PARKING)
                && !Validators.isOfType(object, BuildingTag.class, BuildingTag.ROOF);
    }

    @Override
    protected Optional<CheckFlag> flag(final AtlasObject object)
    {
        final Area building = (Area) object;
        final Iterable<Edge> intersectingEdges = Iterables.filter(building.getAtlas()
                .edgesIntersecting(building.bounds(), intersectsCoreWayInvalidly(building)),
                ignoreTags());
        final CheckFlag flag = new CheckFlag(getTaskIdentifier(building));
        flag.addObject(building);
        this.handleIntersections(intersectingEdges, flag, building);

        if (flag.getPolyLines().size() > 1)
        {
            return Optional.of(flag);
        }

        return Optional.empty();
    }

    @Override
    protected List<String> getFallbackInstructions()
    {
        return FALLBACK_INSTRUCTIONS;
    }

    /**
     * Loops through all intersecting {@link Edge}s, and keeps track of reverse and already seen
     * intersections
     *
     * @param intersectingEdges
     *            all intersecting {@link Edge}s for given building
     * @param flag
     *            the {@link CheckFlag} we're updating
     * @param building
     *            the building being processed
     */
    private void handleIntersections(final Iterable<Edge> intersectingEdges, final CheckFlag flag,
            final Area building)
    {
        final Set<Edge> knownIntersections = new HashSet<>();
        for (final Edge edge : intersectingEdges)
        {
            if (!knownIntersections.contains(edge))
            {
                flag.addObject(edge, this.getLocalizedInstruction(0, building.getOsmIdentifier(),
                        edge.getOsmIdentifier()));

                knownIntersections.add(edge);
                if (edge.hasReverseEdge())
                {
                    knownIntersections.add(edge.reversed().get());
                }
            }
        }
    }
}
