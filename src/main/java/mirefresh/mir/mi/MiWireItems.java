package mirefresh.mir.mi;

import mirefresh.mir.Mir;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.patryk3211.powergrid.electricity.wire.WireItem;

/**
 * One PowerGrid {@link WireItem} per MI cable material that PowerGrid doesn't already cover
 * (copper, iron and gold wires already exist in PowerGrid) — Tin, Silver, Cupronickel, Electrum,
 * Aluminum, Kanthal, Annealed Copper, Platinum and Superconductor, matching MI's own material list
 * exactly (see {@code aztech.modern_industrialization.materials.MIMaterials}: each of these builds a
 * cable at one specific MI voltage tier).
 *
 * <p>All nine share one wire "class" (same resistance, thickness, and the same reused PowerGrid
 * wire texture/render) and differ only in {@code maximumCurrent} — NOT MI's own EU/t numbers
 * directly (32/128/1024/8192/128,000,000): that ladder is wildly exponential, would leave LV
 * weaker than PowerGrid's own plain copper wire (80A), and — since both materials in an MI tier
 * would land on the exact same number — leaves no reason to ever craft the pricier one. Instead a
 * flat, 9-step linear ramp from 100A to 500A (step 50A), ordered within each tier by the two
 * materials' real-world conductivity (see the {@code data/mir/powergrid/wire_types/*.json} entries
 * this registers against): Tin 100A &lt; Silver 150A (LV: silver conducts better than tin) &lt;
 * Cupronickel 200A &lt; Electrum 250A (MV: electrum's silver content beats cupronickel, an
 * alloy chosen for corrosion resistance over conductivity) &lt; Kanthal 300A &lt; Aluminum 350A
 * (HV: kanthal is a real-world *resistance*-heating alloy, deliberately a poor conductor) &lt;
 * Platinum 400A &lt; Annealed Copper 450A (EV: annealed copper is the real-world conductivity
 * benchmark) &lt; Superconductor 500A (SV, alone at the top). There's deliberately no
 * per-material voltage rating either: PowerGrid has no such concept on a wire itself — voltage is
 * whatever the connected circuit settles at — so using an under-tier wire on a higher-power
 * connector simply drives it over its
 * {@code maximumCurrent} and it burns out on its own, exactly like a real undersized cable.
 */
public final class MiWireItems {

    public static final DeferredHolder<Item, WireItem> TIN_WIRE =
            Mir.ITEMS.register("tin_wire", () -> new WireItem(new Item.Properties()));
    public static final DeferredHolder<Item, WireItem> SILVER_WIRE =
            Mir.ITEMS.register("silver_wire", () -> new WireItem(new Item.Properties()));
    public static final DeferredHolder<Item, WireItem> CUPRONICKEL_WIRE =
            Mir.ITEMS.register("cupronickel_wire", () -> new WireItem(new Item.Properties()));
    public static final DeferredHolder<Item, WireItem> ELECTRUM_WIRE =
            Mir.ITEMS.register("electrum_wire", () -> new WireItem(new Item.Properties()));
    public static final DeferredHolder<Item, WireItem> ALUMINUM_WIRE =
            Mir.ITEMS.register("aluminum_wire", () -> new WireItem(new Item.Properties()));
    public static final DeferredHolder<Item, WireItem> KANTHAL_WIRE =
            Mir.ITEMS.register("kanthal_wire", () -> new WireItem(new Item.Properties()));
    public static final DeferredHolder<Item, WireItem> ANNEALED_COPPER_WIRE =
            Mir.ITEMS.register("annealed_copper_wire", () -> new WireItem(new Item.Properties()));
    public static final DeferredHolder<Item, WireItem> PLATINUM_WIRE =
            Mir.ITEMS.register("platinum_wire", () -> new WireItem(new Item.Properties()));
    public static final DeferredHolder<Item, WireItem> SUPERCONDUCTOR_WIRE =
            Mir.ITEMS.register("superconductor_wire", () -> new WireItem(new Item.Properties()));

    private MiWireItems() {}

    /** Force class-load so the static {@code register(...)} calls above run. */
    public static void init() {}
}
