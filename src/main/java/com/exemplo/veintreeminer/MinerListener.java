package com.exemplo.veintreeminer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class MinerListener implements Listener {

    private static final String PERMISSION = "veintreeminer.use";
    private static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
            BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final VeinTreeMiner plugin;

    /** Material do minério -> "família" (ex.: iron_ore e deepslate_iron_ore são a mesma). */
    private final Map<Material, String> oreFamilies = new EnumMap<>(Material.class);

    /** Jogadores que estão com uma mineração em andamento (evita recursão). */
    private final Set<UUID> working = new HashSet<>();

    public MinerListener(VeinTreeMiner plugin) {
        this.plugin = plugin;

        family("coal", Tag.COAL_ORES);
        family("iron", Tag.IRON_ORES);
        family("copper", Tag.COPPER_ORES);
        family("gold", Tag.GOLD_ORES);
        family("redstone", Tag.REDSTONE_ORES);
        family("lapis", Tag.LAPIS_ORES);
        family("diamond", Tag.DIAMOND_ORES);
        family("emerald", Tag.EMERALD_ORES);
        oreFamilies.put(Material.NETHER_QUARTZ_ORE, "quartz");
        oreFamilies.put(Material.NETHER_GOLD_ORE, "nether_gold");
        oreFamilies.put(Material.ANCIENT_DEBRIS, "debris");
    }

    private void family(String name, Tag<Material> tag) {
        for (Material material : tag.getValues()) {
            oreFamilies.put(material, name);
        }
    }

    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL) return;
        if (working.contains(player.getUniqueId())) return;
        if (!player.hasPermission(PERMISSION) || !plugin.isEnabledFor(player)) return;

        FileConfiguration config = plugin.getConfig();
        Block origin = event.getBlock();
        Material type = origin.getType();
        Material toolType = player.getInventory().getItemInMainHand().getType();

        if (Tag.ITEMS_PICKAXES.isTagged(toolType) && oreFamilies.containsKey(type)) {
            if (!config.getBoolean("ores.enabled", true)) return;
            if (config.getBoolean("ores.require-sneaking", false) && !player.isSneaking()) return;

            working.add(player.getUniqueId());
            try {
                mineVein(player, origin, config.getInt("ores.max-blocks", 64));
            } finally {
                working.remove(player.getUniqueId());
            }
        } else if (Tag.ITEMS_AXES.isTagged(toolType) && Tag.OVERWORLD_NATURAL_LOGS.isTagged(type)) {
            if (!config.getBoolean("trees.enabled", true)) return;
            if (config.getBoolean("trees.require-sneaking", false) && !player.isSneaking()) return;

            working.add(player.getUniqueId());
            try {
                chopTree(player, origin);
            } finally {
                working.remove(player.getUniqueId());
            }
        }
    }

    /* ------------------------------ MINÉRIOS ------------------------------ */

    private void mineVein(Player player, Block origin, int maxBlocks) {
        String family = oreFamilies.get(origin.getType());
        List<Block> ores = collect(origin, maxBlocks,
                block -> family.equals(oreFamilies.get(block.getType())));

        for (Block ore : ores) {
            if (!toolIsUsable(player, Tag.ITEMS_PICKAXES)) {
                warnLowDurability(player);
                return;
            }
            if (!family.equals(oreFamilies.get(ore.getType()))) continue;

            // Quebra como se o jogador tivesse minerado: drops, fortuna/toque suave,
            // XP, proteções de outros plugins e desgaste de 1 de durabilidade por bloco.
            player.breakBlock(ore);
        }
    }

    /* ------------------------------ ÁRVORES ------------------------------ */

    private void chopTree(Player player, Block origin) {
        FileConfiguration config = plugin.getConfig();

        List<Block> logs = collect(origin, config.getInt("trees.max-logs", 256),
                block -> Tag.OVERWORLD_NATURAL_LOGS.isTagged(block.getType()));

        List<Block> trunk = new ArrayList<>(logs);
        trunk.add(origin);

        if (config.getBoolean("trees.only-natural-trees", true) && !touchesNaturalLeaves(trunk)) {
            return; // provavelmente uma construção: quebra normal, só 1 tronco
        }

        List<Block> leaves = collectLeaves(trunk, config.getInt("trees.max-leaves", 1024));

        // 1) Troncos: cada um gasta durabilidade do machado.
        for (Block log : logs) {
            if (!toolIsUsable(player, Tag.ITEMS_AXES)) {
                warnLowDurability(player);
                return; // árvore incompleta: não mexe nas folhas
            }
            if (!Tag.OVERWORLD_NATURAL_LOGS.isTagged(log.getType())) continue;
            player.breakBlock(log);
        }

        // 2) Folhas: soltam o loot normal (mudas, gravetos, maçãs) e NÃO gastam durabilidade.
        ItemStack tool = player.getInventory().getItemInMainHand();
        for (Block leaf : leaves) {
            if (!(leaf.getBlockData() instanceof Leaves data) || data.isPersistent()) continue;
            if (!mayBreak(player, leaf)) continue;
            leaf.breakNaturally(tool);
        }
    }

    private boolean touchesNaturalLeaves(List<Block> trunk) {
        for (Block log : trunk) {
            for (BlockFace face : FACES) {
                Block next = log.getRelative(face);
                if (Tag.LEAVES.isTagged(next.getType())
                        && next.getBlockData() instanceof Leaves data
                        && !data.isPersistent()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Folhas ligadas ao tronco, seguindo a "distância" que o próprio jogo usa
     * (1 = encostada no tronco ... 6 = a mais longe). Folhas colocadas por jogadores
     * (persistentes) são ignoradas.
     */
    private List<Block> collectLeaves(List<Block> trunk, int max) {
        List<Block> result = new ArrayList<>();
        Map<Block, Integer> depth = new HashMap<>();
        Deque<Block> queue = new ArrayDeque<>();

        for (Block log : trunk) {
            depth.put(log, 0);
            queue.add(log);
        }

        while (!queue.isEmpty()) {
            Block current = queue.poll();
            int currentDepth = depth.get(current);
            if (currentDepth >= 6) continue;

            for (BlockFace face : FACES) {
                Block next = current.getRelative(face);
                if (depth.containsKey(next) || !isLoaded(next)) continue;
                if (!Tag.LEAVES.isTagged(next.getType())) continue;
                if (!(next.getBlockData() instanceof Leaves data) || data.isPersistent()) continue;
                if (data.getDistance() != currentDepth + 1) continue;

                depth.put(next, currentDepth + 1);
                queue.add(next);
                result.add(next);
                if (result.size() >= max) return result;
            }
        }
        return result;
    }

    /** Pergunta aos outros plugins (proteção/claims) se o jogador pode quebrar esta folha. */
    private boolean mayBreak(Player player, Block block) {
        BlockBreakEvent check = new BlockBreakEvent(block, player);
        check.setDropItems(false);
        plugin.getServer().getPluginManager().callEvent(check);
        return !check.isCancelled();
    }

    /* ------------------------------ UTILITÁRIOS ------------------------------ */

    /** Busca em largura (incluindo diagonais) de blocos conectados que passam no filtro. */
    private List<Block> collect(Block origin, int max, Predicate<Block> matches) {
        List<Block> result = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        seen.add(origin);
        queue.add(origin);

        while (!queue.isEmpty() && result.size() < max) {
            Block current = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block next = current.getRelative(dx, dy, dz);
                        if (!isLoaded(next) || !seen.add(next) || !matches.test(next)) continue;

                        result.add(next);
                        queue.add(next);
                        if (result.size() >= max) return result;
                    }
                }
            }
        }
        return result;
    }

    private static boolean isLoaded(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    /**
     * Confere se a ferramenta na mão ainda é do tipo certo e se tem durabilidade de sobra.
     * Para antes de quebrar a ferramenta (reserva 2 pontos, 1 deles para o bloco original).
     */
    private boolean toolIsUsable(Player player, Tag<Material> toolTag) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!toolTag.isTagged(hand.getType())) return false;

        int max = hand.getType().getMaxDurability();
        if (max <= 0) return true;

        if (hand.getItemMeta() instanceof Damageable meta) {
            if (meta.isUnbreakable()) return true;
            return max - meta.getDamage() > 2;
        }
        return true;
    }

    private void warnLowDurability(Player player) {
        player.sendActionBar(Component.text(
                "Sua ferramenta está quase quebrando, parei por aqui!", NamedTextColor.YELLOW));
    }
}
