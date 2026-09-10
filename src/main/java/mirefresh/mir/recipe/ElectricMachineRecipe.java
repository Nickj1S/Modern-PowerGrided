package mirefresh.mir.recipe;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import mirefresh.mir.Mir;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;

/**
 * A single-input, single-output electric machine recipe. {@code energy} is the amount of grid
 * energy (joules = watt-seconds) the operation costs; {@code minDuration} is a floor on how many
 * ticks it takes even with unlimited power (so operations are not instant on a strong grid).
 *
 * <p>JSON (data/mir/recipe/&lt;name&gt;.json):
 * <pre>
 * {
 *   "type": "mir:electric_furnace",
 *   "ingredient": { "item": "minecraft:raw_iron" },
 *   "result":     { "id": "minecraft:iron_ingot", "count": 1 },
 *   "energy": 4000.0,
 *   "min_duration": 100
 * }
 * </pre>
 */
public record ElectricMachineRecipe(RecipeType<ElectricMachineRecipe> type,
                                    RecipeSerializer<ElectricMachineRecipe> serializer,
                                    Ingredient ingredient,
                                    ItemStack result,
                                    double energy,
                                    int minDuration) implements Recipe<SingleRecipeInput> {

    @Override
    public boolean matches(SingleRecipeInput input, Level level) {
        return ingredient.test(input.item());
    }

    @Override
    public ItemStack assemble(SingleRecipeInput input, HolderLookup.Provider registries) {
        return result.copy();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return result;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return serializer;
    }

    @Override
    public RecipeType<?> getType() {
        return type;
    }

    /** One serializer instance per machine recipe type (electric_furnace, compressor, ...). */
    public static final class Serializer implements RecipeSerializer<ElectricMachineRecipe> {
        private final RecipeTypeHolder holder;
        private final MapCodec<ElectricMachineRecipe> codec;
        private final StreamCodec<RegistryFriendlyByteBuf, ElectricMachineRecipe> streamCodec;

        public Serializer(RecipeTypeHolder holder) {
            this.holder = holder;
            this.codec = RecordCodecBuilder.mapCodec(in -> in.group(
                    Ingredient.CODEC_NONEMPTY.fieldOf("ingredient").forGetter(ElectricMachineRecipe::ingredient),
                    ItemStack.STRICT_CODEC.fieldOf("result").forGetter(ElectricMachineRecipe::result),
                    com.mojang.serialization.Codec.DOUBLE.fieldOf("energy").forGetter(ElectricMachineRecipe::energy),
                    com.mojang.serialization.Codec.INT.optionalFieldOf("min_duration", 20).forGetter(ElectricMachineRecipe::minDuration)
            ).apply(in, (ing, res, e, d) -> new ElectricMachineRecipe(holder.type(), this, ing, res, e, d)));

            this.streamCodec = StreamCodec.composite(
                    Ingredient.CONTENTS_STREAM_CODEC, ElectricMachineRecipe::ingredient,
                    ItemStack.STREAM_CODEC, ElectricMachineRecipe::result,
                    ByteBufCodecs.DOUBLE, ElectricMachineRecipe::energy,
                    ByteBufCodecs.VAR_INT, ElectricMachineRecipe::minDuration,
                    (ing, res, e, d) -> new ElectricMachineRecipe(holder.type(), this, ing, res, e, d)
            );
        }

        @Override
        public MapCodec<ElectricMachineRecipe> codec() {
            return codec;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, ElectricMachineRecipe> streamCodec() {
            return streamCodec;
        }
    }

    /** Lazy back-reference so a Serializer can name its RecipeType before both are registered. */
    public interface RecipeTypeHolder {
        RecipeType<ElectricMachineRecipe> type();
    }

    public static RecipeType<ElectricMachineRecipe> newType(String name) {
        final String id = name;
        return new RecipeType<>() {
            @Override
            public String toString() {
                return Mir.MODID + ":" + id;
            }
        };
    }
}
