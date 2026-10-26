package fr.deadsmp.aventurier;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Gère le PNJ Aventurier : création, modèle ModelEngine, clic, protections. */
public final class NpcManager implements Listener {

    private final AventurierPlugin plugin;
    private final NamespacedKey npcKey;
    private final NamespacedKey modelKey;
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public NpcManager(AventurierPlugin plugin) {
        this.plugin = plugin;
        this.npcKey = new NamespacedKey(plugin, "aventurier_npc");
        this.modelKey = new NamespacedKey(plugin, "aventurier_model");
    }

    /** À appeler une fois les managers prêts. */
    public void start() {
        ModelEngineHook me = plugin.modelEngine();
        if (me != null) {
            me.registerInteract(this, this::handleModelClick);
        }
        // ModelEngine charge ses modèles de façon asynchrone au démarrage : on réessaie plus tard.
        Bukkit.getScheduler().runTaskLater(plugin, this::ensureAllModels, 100L);
        Bukkit.getScheduler().runTaskLater(plugin, this::ensureAllModels, 600L);
    }

    public boolean isNpc(Entity entity) {
        return entity.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ création / suppression

    public Entity spawn(Location where, String modelId) {
        Location loc = where.clone();
        loc.setYaw(loc.getYaw() + 180f); // le PNJ regarde le joueur
        loc.setPitch(0f);
        World world = loc.getWorld();

        Entity entity;
        try {
            entity = world.spawnEntity(loc, baseType());
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("npc.base-entity invalide, villageois utilisé à la place.");
            entity = world.spawnEntity(loc, EntityType.VILLAGER);
        }

        entity.setPersistent(true);
        entity.setInvulnerable(true);
        entity.setSilent(true);
        entity.customName(plugin.mm(plugin.getConfig().getString("npc.name", "<gold><bold>Aventurier")));
        entity.setCustomNameVisible(plugin.getConfig().getBoolean("npc.show-name", true));
        if (entity instanceof LivingEntity living) {
            living.setAI(false);
            living.setRemoveWhenFarAway(false);
            living.setCollidable(false);
        }
        entity.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(modelKey, PersistentDataType.STRING, modelId);

        ensureModel(entity);
        return entity;
    }

    public Entity findNearest(Player player, double radius) {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : player.getNearbyEntities(radius, radius, radius)) {
            if (!isNpc(e)) continue;
            double d = e.getLocation().distanceSquared(player.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    private EntityType baseType() {
        String name = plugin.getConfig().getString("npc.base-entity", "villager");
        try {
            EntityType type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
            if (type != null) return type;
        } catch (IllegalArgumentException ignored) {
            // nom invalide : on retombe sur le villageois
        }
        return EntityType.VILLAGER;
    }

    // ------------------------------------------------------------------ ModelEngine

    public void ensureModel(Entity entity) {
        ModelEngineHook me = plugin.modelEngine();
        if (me == null) return;
        String model = entity.getPersistentDataContainer().get(modelKey, PersistentDataType.STRING);
        if (model == null) model = plugin.getConfig().getString("npc.model-id", "aventurier");
        me.apply(entity, model, plugin.getConfig().getBoolean("npc.hide-base-entity", true));
    }

    public void ensureAllModels() {
        if (plugin.modelEngine() == null) return;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (isNpc(entity)) ensureModel(entity);
            }
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (plugin.modelEngine() == null) return;
        for (Entity entity : event.getEntities()) {
            if (isNpc(entity)) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (entity.isValid()) ensureModel(entity);
                }, 20L);
            }
        }
    }

    // ------------------------------------------------------------------ clics

    /** Clic « classique » sur l'entité de base. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!isNpc(clicked)) return;
        event.setCancelled(true); // pas de fenêtre de commerce du villageois
        if (event.getHand() != EquipmentSlot.HAND) return;
        handleClick(event.getPlayer());
    }

    /** Clic sur la hitbox du modèle ModelEngine. */
    private void handleModelClick(Player player, Entity entity) {
        if (!isNpc(entity)) return;
        handleClick(player);
    }

    private void handleClick(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastClick.get(player.getUniqueId());
        if (last != null && now - last < 400L) return; // les deux sources de clic peuvent se doubler
        lastClick.put(player.getUniqueId(), now);

        // Peut venir d'un autre thread (paquets) : on repasse sur le thread principal
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (!player.hasPermission("deadsmp.use")) {
                plugin.send(player, "no-permission");
                return;
            }
            new AventurierMenu(plugin, player).open();
        });
    }

    // ------------------------------------------------------------------ protections

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (isNpc(event.getEntity())) event.setCancelled(true); // même les joueurs en créatif
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastClick.remove(event.getPlayer().getUniqueId());
    }
}
