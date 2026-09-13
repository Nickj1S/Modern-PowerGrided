package mirefresh.mir.mi;

import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * Implemented (by mixin) on MI's {@code MachineBlockEntity} so its block class / PowerGrid can
 * reach the hidden {@link MiElectricCompanion} that carries the electrical behaviour.
 */
public interface MiElectricHolder {

    /** The companion for this machine, creating it on first call if this machine is electrified. */
    @Nullable
    MiElectricCompanion mir$companion();

    /** The companion if one already exists — never creates. */
    @Nullable
    MiElectricCompanion mir$peekCompanion();

    /** Whether this machine has been electrified (a connector + companion). */
    boolean mir$electrified();

    /**
     * Whether this machine gets the 5-terminal bidirectional buffer connector (storage units) as
     * opposed to the 2-terminal input-only consumer connector or the 3-terminal output-only
     * generator connector. Only meaningful when {@link #mir$electrified()} is true.
     */
    boolean mir$isBuffer();

    /**
     * Whether this machine gets the 3-terminal output-only generator connector (a
     * {@code GeneratorMachineBlockEntity}) as opposed to the buffer or plain consumer connector.
     * Only meaningful when {@link #mir$electrified()} is true. Public (not package-private) because
     * {@code EnergyHelperMixin} needs it too, to cancel MI's own EU auto-push for generators just
     * like it already does for buffers.
     */
    boolean mir$isGenerator();

    /**
     * Which face the connector sits on — independent of MI's own {@code outputDirection}/
     * {@code facingDirection}. Set only by an edge-zone wrench click (see the {@code useWrench}
     * injection on {@code MachineBlockEntityMixin}); center/corner clicks still drive MI's own
     * fields as normal but leave this untouched. Lazily frozen to today's default position (the old
     * outputDirection-or-facing-opposite rule) on first read, so existing worlds don't jump.
     *
     * <p>When {@link #mir$connectorEdge()} is non-null, this and it together name one of the 12
     * physical block edges the connector is mounted on (see {@code ConnectorModelWrapper.Placement});
     * on its own (edge {@code null}) it's a plain face mount.
     */
    Direction mir$connectorFace();

    /**
     * The second direction of the edge the connector is mounted on, or {@code null} for a plain face
     * mount. Only ever set for the 2-terminal consumer connector (see
     * {@code MachineBlockEntityMixin#mir$useWrenchOnConnector}) — the 5-terminal buffer connector has
     * no edge-specific models, so it always renders as a plain {@link #mir$connectorFace()} mount.
     */
    @Nullable
    Direction mir$connectorEdge();
}
