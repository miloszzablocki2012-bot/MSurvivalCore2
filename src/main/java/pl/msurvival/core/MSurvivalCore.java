package pl.msurvival.core;

import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

@SuppressWarnings("deprecation")
public final class MSurvivalCore extends JavaPlugin implements Listener {
    private File dataFile;
    private YamlConfiguration data;
    private NamespacedKey menuKey, actionKey, keyKey;
    private final Random random = new Random();
    private final Set<UUID> vanished = new HashSet<>();
    private final Set<UUID> frozen = new HashSet<>();
    private final Set<UUID> logged = new HashSet<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        loadData();
        menuKey = new NamespacedKey(this, "menu");
        actionKey = new NamespacedKey(this, "action");
        keyKey = new NamespacedKey(this, "key_type");
        Bukkit.getPluginManager().registerEvents(this, this);
        commands();
        long ticks = Math.max(5, getConfig().getLong("settings.autosave-seconds", 20)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::saveAll, ticks, ticks);
        Bukkit.getScheduler().runTaskTimer(this, this::refreshAllVisuals, 40L, 100L);
    }

    @Override public void onDisable() {
        saveAll();
        saveData();
    }

    private boolean module(String name) {
        return getConfig().getBoolean("modules." + name, true);
    }

    private void commands() {
        cmd("menu", (s,a)->{ if(s instanceof Player p) openMainMenu(p); });
        cmd("lobby", (s,a)->{ if(s instanceof Player p) toLobby(p, true); });
        cmd("survival", (s,a)->{ if(s instanceof Player p) toSurvival(p, true); });
        cmd("setlobby", (s,a)->{ if(s instanceof Player p && admin(p)) { saveLoc("lobby", p.getLocation()); p.sendMessage(msg("lobby-set")); } });
        cmd("setsurvival", (s,a)->{ if(s instanceof Player p && admin(p)) { saveLoc("survival", p.getLocation()); p.sendMessage(msg("survival-set")); } });
        cmd("keysmenu", (s,a)->{ if(s instanceof Player p) openKeysMenu(p); });
        cmd("kits", (s,a)->{ if(s instanceof Player p) openKitsMenu(p); });
        cmd("daily", (s,a)->{ if(s instanceof Player p) daily(p); });
        cmd("pomoc", (s,a)-> sendHelp(s));
        cmd("komendy", (s,a)-> sendConfigLines(s, "info.komendy"));
        cmd("regulamin", (s,a)-> sendConfigLines(s, "info.regulamin"));
        cmd("rules", (s,a)-> sendConfigLines(s, "info.regulamin"));
        cmd("social", (s,a)-> sendConfigLines(s, "info.social"));
        cmd("fanpage", (s,a)-> sendConfigLines(s, "info.social"));
        cmd("tiktok", (s,a)-> sendConfigLines(s, "info.social"));
        cmd("youtube", (s,a)-> sendConfigLines(s, "info.social"));
        cmd("rangi", (s,a)-> sendConfigLines(s, "info.rangi"));
        cmd("vip", (s,a)-> sendConfigLines(s, "info.rangi"));
        cmd("klucze", (s,a)-> sendConfigLines(s, "info.klucze"));
        cmd("start", (s,a)-> sendConfigLines(s, "welcome-book.lines"));
        cmd("donate", (s,a)-> sendConfigLines(s, "info.donate"));
        cmd("wsparcie", (s,a)-> sendConfigLines(s, "info.donate"));
        cmd("discord", (s,a)-> s.sendMessage(color("&9&lDiscord &8» &b" + getConfig().getString("server.discord"))));
        cmd("core", (s,a)->{ if(!s.hasPermission("msurvival.admin")) { s.sendMessage(msg("no-permission")); return; } reloadConfig(); s.sendMessage(msg("reload")); });

        cmd("keyadmin", (s,a)-> {
            if(!s.hasPermission("msurvival.keys")) { s.sendMessage(msg("no-permission")); return; }
            if(a.length >= 1 && a[0].equalsIgnoreCase("reload")) { reloadConfig(); s.sendMessage(msg("reload")); return; }
            if(a.length < 3) { s.sendMessage(color("&c/keyadmin <give|item|reset> <gracz> <klucz> [ilosc]")); return; }
            String mode = a[0].toLowerCase(Locale.ROOT), player = a[1], key = norm(a[2]);
            int amount = a.length >= 4 ? parseInt(a[3]) : 1;
            if(mode.equals("reset")) { data.set(path(player) + ".lastWeekly", 0L); saveData(); return; }
            if(!getConfig().contains("keys." + key)) { s.sendMessage(color("&cNie ma takiego klucza.")); return; }
            if(mode.equals("give")) { setKeys(player, key, getKeys(player, key) + amount); s.sendMessage(color("&aDodano " + amount + "x " + display(key) + " &adla " + player)); return; }
            if(mode.equals("item")) { Player t = Bukkit.getPlayerExact(player); if(t != null) t.getInventory().addItem(keyItem(key, amount)); return; }
        });

        cmd("setrank", (s,a)->{ if(!rankAdmin(s)) return; if(a.length < 2) return; setRank(s, a[0], a[1], 0L, null); });
        cmd("temprank", (s,a)->{ if(!rankAdmin(s)) return; if(a.length < 3) return; long d=parseDuration(a[2]); if(d<=0){s.sendMessage(color("&cZły czas.")); return;} setRank(s,a[0],a[1],System.currentTimeMillis()+d,a[2]); });
        cmd("setranktemp", (s,a)->{ if(!rankAdmin(s)) return; if(a.length < 3) return; long d=parseDuration(a[2]); if(d<=0){s.sendMessage(color("&cZły czas.")); return;} setRank(s,a[0],a[1],System.currentTimeMillis()+d,a[2]); });
        cmd("rankremove", (s,a)->{ if(!rankAdmin(s)) return; if(a.length<1)return; OfflinePlayer op=Bukkit.getOfflinePlayer(a[0]); data.set("ranks."+op.getUniqueId(), null); saveData(); Player online=Bukkit.getPlayerExact(a[0]); if(online!=null) applyVisuals(online); s.sendMessage(msg("rank-remove").replace("%player%", a[0])); });
        cmd("ranks", (s,a)-> sendRanks(s));
        cmd("rank", (s,a)->{ if(s instanceof Player p) s.sendMessage(color("&aTwoja ranga: " + rankDisplay(getRank(p)))); });

        cmd("vanish", (s,a)->{ if(!(s instanceof Player p) || !admin(p)) return; if(vanished.remove(p.getUniqueId())) { for(Player o:Bukkit.getOnlinePlayers()) o.showPlayer(this,p); p.sendMessage(msg("vanish-off")); } else { vanished.add(p.getUniqueId()); for(Player o:Bukkit.getOnlinePlayers()) if(!o.hasPermission("msurvival.admin")) o.hidePlayer(this,p); p.sendMessage(msg("vanish-on")); } });
        cmd("fly", (s,a)->{ if(s instanceof Player p && admin(p)) p.setAllowFlight(!p.getAllowFlight()); });
        cmd("gm", (s,a)->{ if(!(s instanceof Player p) || !admin(p) || a.length<1) return; p.setGameMode(switch(a[0]){case"0"->GameMode.SURVIVAL;case"1"->GameMode.CREATIVE;case"2"->GameMode.ADVENTURE;case"3"->GameMode.SPECTATOR;default->p.getGameMode();}); });
        cmd("heal", (s,a)->{ if(!adminSender(s)) return; Player t=target(s,a); if(t!=null){t.setHealth(t.getMaxHealth()); t.setFoodLevel(20);} });
        cmd("feed", (s,a)->{ if(!adminSender(s)) return; Player t=target(s,a); if(t!=null){t.setFoodLevel(20); t.setSaturation(20);} });
        cmd("invsee", (s,a)->{ if(s instanceof Player p && admin(p) && a.length>0){ Player t=Bukkit.getPlayerExact(a[0]); if(t!=null)p.openInventory(t.getInventory()); } });
        cmd("ecsee", (s,a)->{ if(s instanceof Player p && admin(p) && a.length>0){ Player t=Bukkit.getPlayerExact(a[0]); if(t!=null)p.openInventory(t.getEnderChest()); } });
        cmd("staffchat", (s,a)->{ if(!adminSender(s) || a.length<1)return; String text=String.join(" ",a); for(Player p:Bukkit.getOnlinePlayers()) if(p.hasPermission("msurvival.admin")) p.sendMessage(color("&c[SC] &f"+s.getName()+": &7"+text)); });
        cmd("freeze", (s,a)->{ if(!adminSender(s)||a.length<1)return; Player t=Bukkit.getPlayerExact(a[0]); if(t!=null) frozen.add(t.getUniqueId()); });
        cmd("unfreeze", (s,a)->{ if(!adminSender(s)||a.length<1)return; Player t=Bukkit.getPlayerExact(a[0]); if(t!=null) frozen.remove(t.getUniqueId()); });

        cmd("msban", (s,a)->{ if(!s.hasPermission("msurvival.bans")){s.sendMessage(msg("no-permission")); return;} if(a.length<2)return; String player=a[0], reason=join(a,1); data.set("bans."+player.toLowerCase()+".reason",reason); saveData(); Player t=Bukkit.getPlayerExact(player); if(t!=null)t.kickPlayer(color("&cZbanowano\\n&7Powód: &e"+reason)); s.sendMessage(msg("banned").replace("%player%",player).replace("%reason%",reason)); });
        cmd("msunban", (s,a)->{ if(!s.hasPermission("msurvival.bans"))return; if(a.length<1)return; data.set("bans."+a[0].toLowerCase(),null); saveData(); s.sendMessage(msg("unbanned").replace("%player%",a[0])); });
        cmd("mskick", (s,a)->{ if(!s.hasPermission("msurvival.bans"))return; if(a.length<2)return; Player t=Bukkit.getPlayerExact(a[0]); if(t!=null)t.kickPlayer(color(join(a,1))); });
        cmd("mswarn", (s,a)->{ if(!s.hasPermission("msurvival.bans"))return; if(a.length<2)return; data.set("warns."+a[0].toLowerCase()+"."+System.currentTimeMillis(),join(a,1)); saveData(); Player t=Bukkit.getPlayerExact(a[0]); if(t!=null)t.sendMessage(color("&cOstrzeżenie: &e"+join(a,1))); s.sendMessage(msg("warned").replace("%player%",a[0])); });

        cmd("register", (s,a)->{ if(!(s instanceof Player p))return; if(!module("auth"))return; if(a.length<1)return; String pa="auth."+p.getUniqueId()+".password"; if(data.contains(pa)){p.sendMessage(color("&cMasz już konto. /login"));return;} data.set(pa,hash(a[0])); logged.add(p.getUniqueId()); saveData(); p.sendMessage(color("&aZarejestrowano.")); });
        cmd("login", (s,a)->{ if(!(s instanceof Player p))return; if(!module("auth"))return; if(a.length<1)return; String saved=data.getString("auth."+p.getUniqueId()+".password",""); if(saved.equals(hash(a[0]))){logged.add(p.getUniqueId()); p.sendMessage(color("&aZalogowano."));} else p.sendMessage(color("&cBłędne hasło.")); });
    }

    private interface CommandAction { void run(org.bukkit.command.CommandSender sender, String[] args); }
    private void cmd(String name, CommandAction action) {
        if(getCommand(name) == null) return;
        getCommand(name).setExecutor((sender, command, label, args) -> { action.run(sender,args); return true; });
    }

    @EventHandler public void preLogin(AsyncPlayerPreLoginEvent e) {
        if(!module("bans")) return;
        String reason = data.getString("bans."+e.getName().toLowerCase()+".reason");
        if(reason != null) e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, color("&cZbanowano\\n&7Powód: &e"+reason));
    }

    @EventHandler public void join(PlayerJoinEvent e) {
        Player p=e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if(!p.isOnline()) return;
            if(module("welcome")) welcome(p);
            if(module("inventory")) loadInv(p, group(p.getWorld()));
            if(inLobby(p)) giveMenu(p); else removeMenu(p);
            applyVisuals(p);
            if(module("auth") && !data.contains("auth."+p.getUniqueId()+".password")) p.sendMessage(color("&eZarejestruj się: &6/register <hasło>"));
            else if(module("auth")) p.sendMessage(color("&eZaloguj się: &6/login <hasło>"));
        }, getConfig().getLong("settings.join-delay-ticks",20L));
    }

    @EventHandler public void quit(PlayerQuitEvent e) { saveCurrent(e.getPlayer()); logged.remove(e.getPlayer().getUniqueId()); saveData(); }

    @EventHandler public void world(PlayerChangedWorldEvent e) {
        if(!module("inventory")) return;
        Player p=e.getPlayer();
        String old=group(e.getFrom()), now=group(p.getWorld());
        if(old.equals(now)) return;
        saveInv(p,old);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if(!p.isOnline()) return;
            loadInv(p,now);
            if(inLobby(p)) giveMenu(p); else removeMenu(p);
        }, 2L);
    }

    @EventHandler public void interact(PlayerInteractEvent e) {
        if(authLocked(e.getPlayer())) { e.setCancelled(true); return; }
        Action a=e.getAction();
        if(a!=Action.RIGHT_CLICK_AIR && a!=Action.RIGHT_CLICK_BLOCK) return;
        if(isMenu(e.getItem())) { e.setCancelled(true); openMainMenu(e.getPlayer()); return; }
        if(keyFromItem(e.getItem()) != null) { e.setCancelled(true); e.getPlayer().sendMessage(msg("key-right-click-disabled")); }
    }

    @EventHandler public void invClick(InventoryClickEvent e) {
        if(!(e.getWhoClicked() instanceof Player p)) return;
        String title=e.getView().getTitle();
        if(title.equals(color(getConfig().getString("gui.menu-title")))) { e.setCancelled(true); mainAction(p,e.getCurrentItem()); return; }
        if(title.equals(color(getConfig().getString("gui.keys-title")))) { e.setCancelled(true); keyAction(p,e.getCurrentItem()); return; }
        if(title.equals(color(getConfig().getString("gui.kits-title")))) { e.setCancelled(true); kitAction(p,e.getCurrentItem()); return; }
        if(inLobby(p) && !getConfig().getBoolean("protection.lobby-inventory-click", false) && !bypass(p)) e.setCancelled(true);
    }

    @EventHandler public void chat(AsyncPlayerChatEvent e) {
        if(authLocked(e.getPlayer())) { e.setCancelled(true); return; }
        if(!module("ranks")) return;
        String r=getRank(e.getPlayer());
        e.setFormat(color(getConfig().getString("ranks."+r+".prefix","&7")) + "%1$s &8» &f%2$s");
    }

    @EventHandler public void move(PlayerMoveEvent e) { if(authLocked(e.getPlayer()) || frozen.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void command(PlayerCommandPreprocessEvent e) {
        if(!authLocked(e.getPlayer())) return;
        String m=e.getMessage().toLowerCase(Locale.ROOT);
        if(!(m.startsWith("/login") || m.startsWith("/register"))) e.setCancelled(true);
    }

    @EventHandler public void blockBreak(BlockBreakEvent e){ if(blocked(e.getPlayer(),"block-break") || authLocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void blockPlace(BlockPlaceEvent e){ if(blocked(e.getPlayer(),"block-place") || authLocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void drop(PlayerDropItemEvent e){ if(blocked(e.getPlayer(),"drop") || authLocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void pickup(PlayerPickupItemEvent e){ if(blocked(e.getPlayer(),"pickup") || authLocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void damage(EntityDamageEvent e){ if(e.getEntity() instanceof Player p && inLobby(p) && !getConfig().getBoolean("protection.lobby-damage",false)) e.setCancelled(true); }
    @EventHandler public void pvp(EntityDamageByEntityEvent e){ if(!getConfig().getBoolean("protection.lobby-pvp",false)){ if(e.getDamager() instanceof Player p && inLobby(p)) e.setCancelled(true); if(e.getEntity() instanceof Player p && inLobby(p)) e.setCancelled(true); } }
    @EventHandler public void food(FoodLevelChangeEvent e){ if(e.getEntity() instanceof Player p && inLobby(p) && !getConfig().getBoolean("protection.lobby-hunger",false)){ e.setCancelled(true); p.setFoodLevel(20); } }

    private void welcome(Player p) {
        p.sendTitle(color(getConfig().getString("welcome.title")).replace("%player%",p.getName()), color(getConfig().getString("welcome.subtitle")).replace("%player%",p.getName()), 10, 60, 10);
        if(getConfig().getBoolean("welcome-book.send-on-join", true)) sendConfigLines(p, "welcome-book.lines");
        BossBar bar=Bukkit.createBossBar(color(getConfig().getString("welcome.bossbar")).replace("%player%",p.getName()), BarColor.YELLOW, BarStyle.SOLID);
        bar.addPlayer(p);
        Bukkit.getScheduler().runTaskLater(this, bar::removeAll, 160L);
        String path="welcome."+p.getUniqueId()+".joined";
        if(!data.getBoolean(path,false)) {
            data.set(path,true);
            Bukkit.broadcastMessage(color(getConfig().getString("welcome.first-join-broadcast")).replace("%player%",p.getName()));
            for(String cmd:getConfig().getStringList("welcome.first-join-commands")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%",p.getName()));
            saveData();
        }
    }

    private void daily(Player p) {
        long cd=getConfig().getLong("welcome.daily-cooldown-seconds",86400)*1000L;
        long last=data.getLong("welcome."+p.getUniqueId()+".daily",0);
        long left=cd-(System.currentTimeMillis()-last);
        if(last>0 && left>0) { p.sendMessage(msg("cooldown").replace("%time%",time(left))); return; }
        data.set("welcome."+p.getUniqueId()+".daily",System.currentTimeMillis());
        for(String cmd:getConfig().getStringList("welcome.daily-commands")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%",p.getName()));
        p.sendMessage(msg("daily-claimed"));
        saveData();
    }

    private void openMainMenu(Player p) {
        Inventory inv=Bukkit.createInventory(null,27,color(getConfig().getString("gui.menu-title")));
        fill(inv);
        inv.setItem(10,gui(Material.NETHER_STAR,"&e&lLobby","lobby",List.of("&7Powrót do lobby")));
        inv.setItem(12,gui(Material.GRASS_BLOCK,"&a&lSurvival","survival",List.of("&7Wejście na survival")));
        inv.setItem(14,gui(Material.TRIPWIRE_HOOK,"&6&lKlucze","keys",List.of("&7Twoje klucze")));
        inv.setItem(16,gui(Material.CHEST,"&b&lKity","kits",List.of("&7Kity za klucze")));
        p.openInventory(inv);
    }

    private void mainAction(Player p, ItemStack it) {
        String a=getAction(it); if(a==null) return; p.closeInventory();
        if(a.equals("lobby")) toLobby(p,true);
        if(a.equals("survival")) toSurvival(p,true);
        if(a.equals("keys")) openKeysMenu(p);
        if(a.equals("kits")) openKitsMenu(p);
    }

    private void openKeysMenu(Player p) {
        Inventory inv=Bukkit.createInventory(null,54,color(getConfig().getString("gui.keys-title")));
        fill(inv);
        inv.setItem(4,gui(Material.CHEST,"&a&lCotygodniowy klucz","weekly",List.of("&7Odbierz nagrodę co 7 dni")));
        int slot=10;
        ConfigurationSection s=getConfig().getConfigurationSection("keys");
        if(s!=null) for(String k:s.getKeys(false)) {
            inv.setItem(slot,gui(Material.TRIPWIRE_HOOK,display(k), "withdraw:"+k, List.of("&7Wirtualne: &e"+getKeys(p.getName(),k),"&7Fizyczne: &e"+countPhysical(p,k),"","&eKliknij, aby wyjąć 1 klucz")));
            slot++;
            if(slot==17||slot==26||slot==35) slot+=2;
        }
        p.openInventory(inv);
    }

    private void openKitsMenu(Player p) {
        Inventory inv=Bukkit.createInventory(null,27,color(getConfig().getString("gui.kits-title")));
        fill(inv);
        ConfigurationSection s=getConfig().getConfigurationSection("kits");
        if(s!=null) for(String k:s.getKeys(false)) {
            String req=getConfig().getString("kits."+k+".required-key",k);
            inv.setItem(getConfig().getInt("kits."+k+".slot",13), gui(parseMat(getConfig().getString("kits."+k+".material","CHEST")), getConfig().getString("kits."+k+".name",k), "kit:"+k, List.of("&7Wymagany klucz: "+display(req), "&7Masz: &e"+(getKeys(p.getName(),req)+countPhysical(p,req)), "", "&eKliknij, aby otworzyć")));
        }
        p.openInventory(inv);
    }

    private void keyAction(Player p, ItemStack it) {
        String a=getAction(it); if(a==null) return; p.closeInventory();
        if(a.equals("weekly")) weekly(p);
        if(a.startsWith("withdraw:")) withdraw(p,a.substring(9));
    }

    private void kitAction(Player p, ItemStack it) {
        String a=getAction(it); if(a==null) return; p.closeInventory();
        if(a.startsWith("kit:")) openKit(p,a.substring(4));
    }

    private void weekly(Player p) {
        long cd=getConfig().getLong("weekly.cooldown-seconds")*1000L;
        long last=data.getLong(path(p.getName())+".lastWeekly",0);
        long left=cd-(System.currentTimeMillis()-last);
        if(last>0 && left>0){ p.sendMessage(msg("cooldown").replace("%time%",time(left))); return; }
        String key=roll("weekly.random");
        data.set(path(p.getName())+".lastWeekly",System.currentTimeMillis());
        setKeys(p.getName(),key,getKeys(p.getName(),key)+1);
        p.sendMessage(msg("claimed").replace("%key%",display(key)));
    }

    private void withdraw(Player p, String key) {
        if(getKeys(p.getName(),key)<=0){ p.sendMessage(msg("no-key").replace("%key%",display(key))); return; }
        setKeys(p.getName(),key,getKeys(p.getName(),key)-1);
        p.getInventory().addItem(keyItem(key,1));
        p.sendMessage(msg("withdrawn").replace("%key%",display(key)));
    }

    private void openKit(Player p, String kit) {
        kit=norm(kit);
        if(inLobby(p)){ p.sendMessage(color("&cKity otwieraj na survivalu.")); return; }
        if(!getConfig().contains("kits."+kit)){ p.sendMessage(color("&cNie ma takiego kitu.")); return; }
        String req=getConfig().getString("kits."+kit+".required-key",kit);
        if(!takeKey(p,req)){ p.sendMessage(msg("no-key").replace("%key%",display(req))); return; }
        String reward=kit;
        if(getConfig().contains("kits."+kit+".random-rewards")) reward=roll("kits."+kit+".random-rewards");
        p.sendTitle(color("&6&lOTWIERANIE KITU"), color(getConfig().getString("kits."+kit+".name",kit)), 5, 25, 5);
        String finalReward=reward;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if(!p.isOnline()) return;
            giveKit(p, finalReward);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            p.sendMessage(msg("opened").replace("%kit%", finalReward));
        }, 20L);
    }

    private boolean takeKey(Player p, String k) {
        k=norm(k);
        if(getKeys(p.getName(),k)>0){ setKeys(p.getName(),k,getKeys(p.getName(),k)-1); return true; }
        ItemStack[] c=p.getInventory().getContents();
        for(int i=0;i<c.length;i++) if(k.equals(keyFromItem(c[i]))) {
            if(c[i].getAmount()<=1) p.getInventory().setItem(i,null); else c[i].setAmount(c[i].getAmount()-1);
            return true;
        }
        return false;
    }

    private void giveKit(Player p, String kit) {
        switch(kit) {
            case "klasyczny" -> {
                p.getInventory().addItem(new ItemStack(Material.BREAD,32), named(Material.STONE_SWORD,"&fMiecz Klasyczny"), named(Material.STONE_PICKAXE,"&fKilof Klasyczny"), named(Material.STONE_AXE,"&fSiekiera Klasyczna"), named(Material.STONE_SHOVEL,"&fŁopata Klasyczna"), named(Material.STONE_HOE,"&fMotyka Klasyczna"), new ItemStack(Material.SHIELD));
            }
            case "zelazny" -> {
                armorTools(p, "Żelazny", Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS, Material.IRON_SWORD, Material.IRON_PICKAXE, Material.IRON_AXE, Material.IRON_SHOVEL, Material.IRON_HOE, 1, "&7");
                p.getInventory().addItem(enchant(Material.BOW,"&7Łuk Żelazny",e("power",1),e("unbreaking",1)), new ItemStack(Material.ARROW,32), new ItemStack(Material.GOLDEN_APPLE,2), new ItemStack(Material.SHIELD));
            }
            case "diamentowy" -> {
                armorTools(p, "Diamentowy", Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS, Material.DIAMOND_SWORD, Material.DIAMOND_PICKAXE, Material.DIAMOND_AXE, Material.DIAMOND_SHOVEL, Material.DIAMOND_HOE, 3, "&b");
                p.getInventory().addItem(enchant(Material.BOW,"&bŁuk Diamentowy",e("power",3),e("unbreaking",2)), new ItemStack(Material.ARROW,64), new ItemStack(Material.GOLDEN_APPLE,6), new ItemStack(Material.SHIELD));
            }
            case "epic" -> {
                armorTools(p, "Epic", Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS, Material.DIAMOND_SWORD, Material.DIAMOND_PICKAXE, Material.DIAMOND_AXE, Material.DIAMOND_SHOVEL, Material.DIAMOND_HOE, 4, "&5");
                p.getInventory().addItem(enchant(Material.BOW,"&5Łuk Epic",e("power",4),e("flame",1),e("unbreaking",3)), new ItemStack(Material.GOLDEN_APPLE,12), new ItemStack(Material.TOTEM_OF_UNDYING));
            }
            case "legendarny" -> {
                armorTools(p, "Legendarny", Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS, Material.NETHERITE_SWORD, Material.NETHERITE_PICKAXE, Material.NETHERITE_AXE, Material.NETHERITE_SHOVEL, Material.NETHERITE_HOE, 5, "&6");
                p.getInventory().addItem(enchant(Material.BOW,"&6Łuk Legendarny",e("power",5),e("punch",2),e("flame",1),e("infinity",1),e("unbreaking",3)), enchant(Material.ELYTRA,"&6Elytry Legendarne",e("unbreaking",3),e("mending",1)), new ItemStack(Material.GOLDEN_APPLE,16), new ItemStack(Material.TOTEM_OF_UNDYING,2), new ItemStack(Material.FIREWORK_ROCKET,64));
            }
            case "mityczny" -> {
                armorTools(p, "Mityczny", Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS, Material.NETHERITE_SWORD, Material.NETHERITE_PICKAXE, Material.NETHERITE_AXE, Material.NETHERITE_SHOVEL, Material.NETHERITE_HOE, 5, "&d");
                p.getInventory().addItem(enchant(Material.BOW,"&dŁuk Mityczny",e("power",5),e("punch",2),e("flame",1),e("infinity",1),e("unbreaking",3),e("mending",1)), enchant(Material.CROSSBOW,"&dKusza Mityczna",e("quick_charge",3),e("multishot",1),e("unbreaking",3),e("mending",1)), enchant(Material.ELYTRA,"&dElytry Mityczne",e("unbreaking",3),e("mending",1)), new ItemStack(Material.GOLDEN_APPLE,24), new ItemStack(Material.ENCHANTED_GOLDEN_APPLE,2), new ItemStack(Material.TOTEM_OF_UNDYING,4), new ItemStack(Material.FIREWORK_ROCKET,64));
            }
            case "boski" -> ownerSet(p);
        }
    }

    private void armorTools(Player p, String label, Material h, Material c, Material l, Material b, Material sw, Material pick, Material ax, Material sh, Material hoe, int lvl, String color) {
        int prot=Math.min(4,lvl), sharp=Math.min(5,lvl), eff=Math.min(5,lvl);
        p.getInventory().addItem(
                enchant(h,color+label+" Hełm",e("protection",prot),e("unbreaking",3),e("mending",1)),
                enchant(c,color+label+" Napierśnik",e("protection",prot),e("unbreaking",3),e("mending",1)),
                enchant(l,color+label+" Spodnie",e("protection",prot),e("unbreaking",3),e("mending",1)),
                enchant(b,color+label+" Buty",e("protection",prot),e("feather_falling",Math.min(4,lvl)),e("unbreaking",3),e("mending",1)),
                enchant(sw,color+label+" Miecz",e("sharpness",sharp),e("unbreaking",3),e("mending",1)),
                enchant(pick,color+label+" Kilof",e("efficiency",eff),e("fortune",Math.min(3,lvl)),e("unbreaking",3),e("mending",1)),
                enchant(ax,color+label+" Siekiera",e("efficiency",eff),e("sharpness",sharp),e("unbreaking",3),e("mending",1)),
                enchant(sh,color+label+" Łopata",e("efficiency",eff),e("unbreaking",3),e("mending",1)),
                enchant(hoe,color+label+" Motyka",e("efficiency",eff),e("unbreaking",3),e("mending",1))
        );
    }

    private void ownerSet(Player p) {
        armorTools(p,"BOSKI",Material.NETHERITE_HELMET,Material.NETHERITE_CHESTPLATE,Material.NETHERITE_LEGGINGS,Material.NETHERITE_BOOTS,Material.NETHERITE_SWORD,Material.NETHERITE_PICKAXE,Material.NETHERITE_AXE,Material.NETHERITE_SHOVEL,Material.NETHERITE_HOE,5,"&4&l");
        p.getInventory().addItem(
                enchant(Material.BOW,"&e&lŁuk Boga",e("power",5),e("punch",2),e("flame",1),e("infinity",1),e("unbreaking",3),e("mending",1)),
                enchant(Material.CROSSBOW,"&6&lKusza Boga",e("quick_charge",3),e("multishot",1),e("piercing",4),e("unbreaking",3),e("mending",1)),
                enchant(Material.TRIDENT,"&3&lTrójząb Boga",e("impaling",5),e("loyalty",3),e("channeling",1),e("unbreaking",3),e("mending",1)),
                enchant(Material.ELYTRA,"&f&lElytry Boga",e("unbreaking",3),e("mending",1)),
                enchant(Material.SHIELD,"&6&lTarcza Boga",e("unbreaking",3),e("mending",1)),
                new ItemStack(Material.GOLDEN_APPLE,64),
                new ItemStack(Material.ENCHANTED_GOLDEN_APPLE,16),
                new ItemStack(Material.TOTEM_OF_UNDYING,5),
                new ItemStack(Material.FIREWORK_ROCKET,64)
        );
        Material mace=parseMat("MACE");
        if(mace!=Material.STONE) p.getInventory().addItem(enchant(mace,"&4&lBuzdygan Boga",e("density",5),e("breach",3),e("unbreaking",3),e("mending",1)));
    }

    private String[] e(String name, int level) { return new String[]{name, String.valueOf(level)}; }

    private void toLobby(Player p, boolean send) {
        if(module("inventory")) saveInv(p, group(p.getWorld()));
        if(module("inventory")) loadInv(p, "lobby");
        Location loc=loc("lobby");
        if(loc!=null) p.teleport(loc);
        giveMenu(p);
        p.setFoodLevel(20);
        p.setSaturation(20f);
        if(send) p.sendMessage(msg("lobby"));
    }

    private void toSurvival(Player p, boolean send) {
        if(module("inventory")) saveInv(p, group(p.getWorld()));
        if(module("inventory")) loadInv(p, "survival");
        removeMenu(p);
        Location loc = getConfig().getBoolean("survival.use-bed-spawn") ? p.getBedSpawnLocation() : null;
        if(loc==null) loc=loc("survival");
        if(loc!=null) p.teleport(loc);
        if(send) p.sendMessage(msg("survival"));
    }

    private void saveAll() {
        if(!module("inventory")) return;
        for(Player p:Bukkit.getOnlinePlayers()) saveInv(p, group(p.getWorld()));
        saveData();
    }

    private void saveCurrent(Player p) {
        if(module("inventory")) saveInv(p, group(p.getWorld()));
    }

    private void saveInv(Player p, String g) {
        try {
            String pa="inventories."+p.getUniqueId()+"."+g;
            PlayerInventory inv=p.getInventory();
            data.set(pa+".contents", ser(inv.getContents()));
            data.set(pa+".armor", ser(inv.getArmorContents()));
            data.set(pa+".offhand", ser(new ItemStack[]{inv.getItemInOffHand()}));
            data.set(pa+".level", p.getLevel());
            data.set(pa+".exp", p.getExp());
            data.set(pa+".food", p.getFoodLevel());
            data.set(pa+".saturation", p.getSaturation());
            data.set(pa+".health", p.getHealth());
        } catch(Exception ex) { ex.printStackTrace(); }
    }

    private void loadInv(Player p, String g) {
        try {
            String pa="inventories."+p.getUniqueId()+"."+g;
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
            p.getInventory().setItemInOffHand(null);
            if(data.contains(pa+".contents")) {
                p.getInventory().setContents(deser(data.getString(pa+".contents","")));
                p.getInventory().setArmorContents(deser(data.getString(pa+".armor","")));
                ItemStack[] off=deser(data.getString(pa+".offhand",""));
                if(off.length>0) p.getInventory().setItemInOffHand(off[0]);
                p.setLevel(data.getInt(pa+".level",0));
                p.setExp((float)data.getDouble(pa+".exp",0));
                p.setFoodLevel(data.getInt(pa+".food",20));
                p.setSaturation((float)data.getDouble(pa+".saturation",20));
                double hp=data.getDouble(pa+".health",p.getMaxHealth());
                p.setHealth(Math.max(1,Math.min(p.getMaxHealth(),hp)));
            }
            p.updateInventory();
        } catch(Exception ex) { ex.printStackTrace(); }
    }

    private String group(World w) {
        if(w!=null && w.getName().equalsIgnoreCase(getConfig().getString("worlds.lobby-world"))) return "lobby";
        return "survival";
    }

    private void setRank(org.bukkit.command.CommandSender s,String player,String rank,long exp,String timeText) {
        rank=norm(rank);
        if(!getConfig().contains("ranks."+rank)) { s.sendMessage(color("&cNie ma takiej rangi.")); return; }
        OfflinePlayer op=Bukkit.getOfflinePlayer(player);
        data.set("ranks."+op.getUniqueId()+".name",player);
        data.set("ranks."+op.getUniqueId()+".rank",rank);
        data.set("ranks."+op.getUniqueId()+".expires",exp);
        saveData();
        Player online=Bukkit.getPlayerExact(player);
        if(online!=null) applyVisuals(online);
        s.sendMessage((exp>0?msg("rank-temp").replace("%time%",timeText):msg("rank-set")).replace("%player%",player).replace("%rank%",rankDisplay(rank)));
    }

    private String getRank(Player p) {
        long exp=data.getLong("ranks."+p.getUniqueId()+".expires",0);
        if(exp>0 && exp<System.currentTimeMillis()) {
            data.set("ranks."+p.getUniqueId(),null);
            saveData();
        }
        String r=data.getString("ranks."+p.getUniqueId()+".rank","gracz");
        return getConfig().contains("ranks."+r) ? r : "gracz";
    }

    private void refreshAllVisuals() {
        if(!module("ranks")) return;
        for(Player p:Bukkit.getOnlinePlayers()) applyVisuals(p);
    }

    private void applyVisuals(Player p) {
        if(!module("ranks")) return;
        String r=getRank(p);
        String tab=color(getConfig().getString("ranks."+r+".tab-prefix","") + getConfig().getString("ranks."+r+".name-color","&7") + p.getName());
        p.setPlayerListName(tab.length()>80 ? tab.substring(0,80) : tab);
        updateSidebar(p);
        for(Player viewer:Bukkit.getOnlinePlayers()) addNametag(viewer,p,r);
    }

    private void updateSidebar(Player p) {
        if(!getConfig().getBoolean("sidebar.enabled",true)) return;
        Scoreboard b=p.getScoreboard();
        if(b==null || b==Bukkit.getScoreboardManager().getMainScoreboard()) {
            b=Bukkit.getScoreboardManager().getNewScoreboard();
            p.setScoreboard(b);
        }
        Objective old=b.getObjective("mscore");
        if(old!=null) old.unregister();
        Objective o=b.registerNewObjective("mscore","dummy",color(getConfig().getString("sidebar.title","&6&lMSURVIVAL")));
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        List<String> lines=getConfig().getStringList("sidebar.lines");
        int score=lines.size();
        Set<String> used=new HashSet<>();
        for(String raw:lines) {
            String line=color(raw.replace("%player%",p.getName()).replace("%rank%",rankDisplay(getRank(p))).replace("%expires%",expires(p)).replace("%online%",String.valueOf(Bukkit.getOnlinePlayers().size())).replace("%ping%",String.valueOf(p.getPing())).replace("%world%",p.getWorld().getName()));
            while(used.contains(line)) line += ChatColor.RESET;
            used.add(line);
            o.getScore(line.length()>40 ? line.substring(0,40) : line).setScore(score--);
        }
    }

    private void addNametag(Player viewer, Player target, String r) {
        Scoreboard b=viewer.getScoreboard();
        if(b==null || b==Bukkit.getScoreboardManager().getMainScoreboard()) {
            b=Bukkit.getScoreboardManager().getNewScoreboard();
            viewer.setScoreboard(b);
        }
        String tn=("r"+(999-getConfig().getInt("ranks."+r+".priority",0))+"_"+r);
        if(tn.length()>16) tn=tn.substring(0,16);
        Team t=b.getTeam(tn);
        if(t==null) t=b.registerNewTeam(tn);
        t.setPrefix(limit(color(getConfig().getString("ranks."+r+".prefix","&7")),64));
        t.setSuffix(ChatColor.RESET.toString());
        t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
        for(Team other:b.getTeams()) if(!other.getName().equals(tn) && other.hasEntry(target.getName())) other.removeEntry(target.getName());
        if(!t.hasEntry(target.getName())) t.addEntry(target.getName());
    }

    private void sendRanks(org.bukkit.command.CommandSender s) {
        ConfigurationSection cs=getConfig().getConfigurationSection("ranks");
        if(cs==null) return;
        for(String r:cs.getKeys(false)) s.sendMessage(color("&8- &e"+r+" &7=> "+getConfig().getString("ranks."+r+".prefix")));
    }

    private void sendConfigLines(org.bukkit.command.CommandSender s, String path) {
        for(String line : getConfig().getStringList(path)) {
            String out = line;
            if(s instanceof Player p) {
                out = out.replace("%player%", p.getName())
                         .replace("%rank%", module("ranks") ? rankDisplay(getRank(p)) : "")
                         .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                         .replace("%ping%", String.valueOf(p.getPing()))
                         .replace("%world%", p.getWorld().getName());
            }
            out = out.replace("%ip%", getConfig().getString("server.ip", "msurvival.6mc.pl"))
                     .replace("%shop%", getConfig().getString("server.shop", ""))
                     .replace("%donate%", getConfig().getString("server.donate", "https://tipply.pl/@milekz"))
                     .replace("%discord%", getConfig().getString("server.discord", ""));
            s.sendMessage(color(out));
        }
    }

    private void sendHelp(org.bukkit.command.CommandSender s) {
        s.sendMessage(color("&8&m----------------"));
        s.sendMessage(color("&6&lMSURVIVAL &8- &7Komendy"));
        s.sendMessage(color("&e/menu &7- menu"));
        s.sendMessage(color("&e/lobby &7- lobby"));
        s.sendMessage(color("&e/survival &7- survival"));
        s.sendMessage(color("&e/keysmenu &7- klucze"));
        s.sendMessage(color("&e/kits &7- kity"));
        s.sendMessage(color("&e/daily &7- daily"));
        s.sendMessage(color("&8&m----------------"));
    }

    private ItemStack keyItem(String k,int amount) {
        ItemStack it=new ItemStack(parseMat(getConfig().getString("key-item.material","TRIPWIRE_HOOK")), amount);
        ItemMeta m=it.getItemMeta();
        m.setDisplayName(color(getConfig().getString("key-item.name").replace("%key_name%",display(k))));
        ArrayList<String> lore=new ArrayList<>();
        for(String line:getConfig().getStringList("key-item.lore")) lore.add(color(line.replace("%key_name%",display(k)).replace("%key%",k)));
        m.setLore(lore);
        m.addEnchant(Enchantment.UNBREAKING,1,true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS,ItemFlag.HIDE_ATTRIBUTES);
        m.getPersistentDataContainer().set(keyKey, PersistentDataType.STRING, k);
        it.setItemMeta(m);
        return it;
    }

    private String keyFromItem(ItemStack it) {
        if(it==null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(keyKey, PersistentDataType.STRING);
    }

    private int countPhysical(Player p,String k) {
        int c=0;
        for(ItemStack it:p.getInventory().getContents()) if(k.equals(keyFromItem(it))) c+=it.getAmount();
        return c;
    }

    private int getKeys(String player,String k) { return data.getInt(path(player)+".keys."+norm(k),0); }
    private void setKeys(String player,String k,int amount) { data.set(path(player)+".keys."+norm(k),Math.max(0,amount)); saveData(); }
    private String path(String player) { return "players."+player.toLowerCase(Locale.ROOT); }
    private String display(String k) { return getConfig().getString("keys."+norm(k)+".display",k); }

    private String roll(String path) {
        ConfigurationSection s=getConfig().getConfigurationSection(path);
        if(s==null) return "klasyczny";
        int total=0;
        for(String k:s.getKeys(false)) total+=Math.max(0,s.getInt(k));
        int r=random.nextInt(Math.max(1,total))+1;
        int cur=0;
        for(String k:s.getKeys(false)) {
            cur+=Math.max(0,s.getInt(k));
            if(r<=cur) return norm(k);
        }
        return "klasyczny";
    }

    private ItemStack enchant(Material mat, String name, String[]... enchants) {
        ItemStack it=named(mat,name);
        for(String[] e:enchants) {
            Enchantment en=Enchantment.getByKey(NamespacedKey.minecraft(e[0]));
            if(en!=null) it.addUnsafeEnchantment(en,Integer.parseInt(e[1]));
        }
        return it;
    }

    private ItemStack named(Material mat,String name) {
        ItemStack it=new ItemStack(mat);
        ItemMeta im=it.getItemMeta();
        im.setDisplayName(color(name));
        it.setItemMeta(im);
        return it;
    }

    private ItemStack gui(Material mat,String name,String action,List<String> loreRaw) {
        ItemStack it=named(mat,name);
        ItemMeta im=it.getItemMeta();
        ArrayList<String> lore=new ArrayList<>();
        for(String line:loreRaw) lore.add(color(line));
        im.setLore(lore);
        im.getPersistentDataContainer().set(actionKey,PersistentDataType.STRING,action);
        it.setItemMeta(im);
        return it;
    }

    private String getAction(ItemStack it) {
        if(it==null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }

    private void fill(Inventory inv) {
        ItemStack f=named(parseMat(getConfig().getString("gui.filler","BLACK_STAINED_GLASS_PANE"))," ");
        for(int i=0;i<inv.getSize();i++) inv.setItem(i,f);
    }

    private ItemStack menuItem() {
        ItemStack it=named(parseMat(getConfig().getString("menu-item.material","COMPASS")), getConfig().getString("menu-item.name","&6Menu"));
        ItemMeta im=it.getItemMeta();
        ArrayList<String> lore=new ArrayList<>();
        for(String l:getConfig().getStringList("menu-item.lore")) lore.add(color(l));
        im.setLore(lore);
        im.getPersistentDataContainer().set(menuKey,PersistentDataType.STRING,"true");
        it.setItemMeta(im);
        return it;
    }

    private void giveMenu(Player p) {
        if(hasMenu(p)) return;
        int slot=getConfig().getInt("menu-item.slot",4);
        if(slot>=0 && slot<=35) p.getInventory().setItem(slot, menuItem());
        else p.getInventory().addItem(menuItem());
    }

    private boolean hasMenu(Player p) {
        for(ItemStack it:p.getInventory().getContents()) if(isMenu(it)) return true;
        return false;
    }

    private boolean isMenu(ItemStack it) {
        return it!=null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(menuKey, PersistentDataType.STRING);
    }

    private void removeMenu(Player p) {
        ItemStack[] items=p.getInventory().getContents();
        for(int i=0;i<items.length;i++) if(isMenu(items[i])) p.getInventory().setItem(i,null);
    }

    private boolean inLobby(Player p) {
        return p.getWorld().getName().equalsIgnoreCase(getConfig().getString("worlds.lobby-world","Lobby"));
    }

    private boolean blocked(Player p,String key) {
        return inLobby(p) && getConfig().getBoolean("protection.lobby-"+key,true) && !bypass(p);
    }

    private boolean bypass(Player p) {
        return p.hasPermission("msurvival.admin") && p.getGameMode()==GameMode.CREATIVE;
    }

    private boolean authLocked(Player p) {
        if(!module("auth")) return false;
        if(!data.contains("auth."+p.getUniqueId()+".password")) return true;
        return !logged.contains(p.getUniqueId());
    }

    private boolean admin(Player p) {
        if(!p.hasPermission("msurvival.admin")) { p.sendMessage(msg("no-permission")); return false; }
        return true;
    }

    private boolean adminSender(org.bukkit.command.CommandSender s) {
        if(!s.hasPermission("msurvival.admin")) { s.sendMessage(msg("no-permission")); return false; }
        return true;
    }

    private boolean rankAdmin(org.bukkit.command.CommandSender s) {
        if(!s.hasPermission("msurvival.ranks")) { s.sendMessage(msg("no-permission")); return false; }
        return true;
    }

    private Player target(org.bukkit.command.CommandSender s,String[] a) {
        if(a.length>0) return Bukkit.getPlayerExact(a[0]);
        return s instanceof Player p ? p : null;
    }

    private void saveLoc(String key, Location l) {
        getConfig().set(key+".x",l.getX());
        getConfig().set(key+".y",l.getY());
        getConfig().set(key+".z",l.getZ());
        getConfig().set(key+".yaw",l.getYaw());
        getConfig().set(key+".pitch",l.getPitch());
        saveConfig();
    }

    private Location loc(String key) {
        World w=Bukkit.getWorld(key.equals("lobby") ? getConfig().getString("worlds.lobby-world") : getConfig().getString("worlds.survival-world"));
        if(w==null) return null;
        return new Location(w,getConfig().getDouble(key+".x"),getConfig().getDouble(key+".y"),getConfig().getDouble(key+".z"),(float)getConfig().getDouble(key+".yaw"),(float)getConfig().getDouble(key+".pitch"));
    }

    private String ser(ItemStack[] items) throws Exception {
        ByteArrayOutputStream bo=new ByteArrayOutputStream();
        ObjectOutputStream oo=new ObjectOutputStream(bo);
        oo.writeInt(items.length);
        for(ItemStack it:items) oo.writeObject(it);
        oo.close();
        return Base64.getEncoder().encodeToString(bo.toByteArray());
    }

    private ItemStack[] deser(String raw) throws Exception {
        if(raw==null || raw.isBlank()) return new ItemStack[0];
        ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(raw)));
        int len=in.readInt();
        ItemStack[] out=new ItemStack[len];
        for(int i=0;i<len;i++) out[i]=(ItemStack)in.readObject();
        in.close();
        return out;
    }

    private long parseDuration(String raw) {
        try {
            long n=Long.parseLong(raw.substring(0,raw.length()-1));
            char u=raw.charAt(raw.length()-1);
            return switch(u) {
                case 's' -> n*1000L;
                case 'm' -> n*60000L;
                case 'h' -> n*3600000L;
                case 'd' -> n*86400000L;
                case 'w' -> n*604800000L;
                default -> -1L;
            };
        } catch(Exception e) { return -1L; }
    }

    private String time(long ms) {
        if(ms<=0) return "wygasła";
        long s=ms/1000,d=s/86400;
        s%=86400;
        long h=s/3600;
        s%=3600;
        long m=s/60;
        if(d>0) return d+"d "+h+"h";
        if(h>0) return h+"h "+m+"m";
        return Math.max(1,m)+"m";
    }

    private String expires(Player p) {
        long e=data.getLong("ranks."+p.getUniqueId()+".expires",0);
        return e<=0 ? "nigdy" : time(e-System.currentTimeMillis());
    }

    private String rankDisplay(String r) { return color(getConfig().getString("ranks."+r+".display",r)); }

    private String hash(String raw) {
        try {
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            byte[] bytes=md.digest(raw.getBytes());
            StringBuilder sb=new StringBuilder();
            for(byte b:bytes) sb.append(String.format("%02x",b));
            return sb.toString();
        } catch(Exception e) { return raw; }
    }

    private String join(String[] arr,int start) {
        StringBuilder sb=new StringBuilder();
        for(int i=start;i<arr.length;i++) { if(i>start) sb.append(' '); sb.append(arr[i]); }
        return sb.toString();
    }

    private String limit(String raw,int max) {
        return raw.length()>max ? raw.substring(0,max) : raw;
    }

    private int parseInt(String s) {
        try { return Math.max(1,Integer.parseInt(s)); } catch(Exception e) { return 1; }
    }

    private Material parseMat(String raw) {
        try { return Material.valueOf(raw.toUpperCase(Locale.ROOT)); } catch(Exception e) { return Material.STONE; }
    }

    private String norm(String raw) { return raw==null ? "" : raw.toLowerCase(Locale.ROOT); }

    private void loadData() {
        dataFile=new File(getDataFolder(),"data.yml");
        if(!dataFile.exists()) {
            try { getDataFolder().mkdirs(); dataFile.createNewFile(); } catch(Exception e) { e.printStackTrace(); }
        }
        data=YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveData() {
        try { data.save(dataFile); } catch(Exception e) { e.printStackTrace(); }
    }

    private String msg(String key) {
        return color(getConfig().getString("messages.prefix","") + getConfig().getString("messages."+key,""));
    }

    private String color(String raw) {
        return raw==null ? "" : ChatColor.translateAlternateColorCodes('&',raw);
    }
}
