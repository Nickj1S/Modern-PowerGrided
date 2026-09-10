package mirefresh.mir.mi;

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
}
