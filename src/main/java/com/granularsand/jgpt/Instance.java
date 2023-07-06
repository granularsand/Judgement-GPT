package com.granularsand.jgpt;

import com.google.gson.Gson;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Tuple;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.brewing.PlayerBrewedPotionEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import java.io.IOException;
import java.util.*;

@Mod.EventBusSubscriber(modid = JGPT.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class Instance {
    public static final Gson GSON = new Gson();
    private static final Logger LOGGER = LogUtils.getLogger();
    ArrayList<OpenAI.Message> messages;
    final ArrayList<Tuple<String, Integer>> event_submission_list = new ArrayList<>();
    MinecraftServer server;
    final HashSet<String> mindread_subscriptions = new HashSet<>();
    final ArrayList<String> queued_msgs = new ArrayList<>();
    int nticks_per_second = 40;
    int nmax_tokens = 1024*3;
    int submission_cooldown = nticks_per_second*2;
    int submission_persistence_tick = 0;
    int ticks_per_persistence = nticks_per_second * 30;
    int submission_cooldown_tick = 0;
    int event_saturation = 0;
    int event_saturation_per_event = submission_cooldown *2;
    int event_staturated_ticks = 0;
    int nticks_since_last_submit = 0;
    int event_staturated_ticks_threshold = event_saturation_per_event*4;
    int ninvalid_commands_threshold = 2;
    int ninvalid_commands_left = ninvalid_commands_threshold;
    public OpenAI.Message create_msg() {
        var m = new OpenAI.Message();
        messages.add(m);
        return m;
    }
    public void send_msg_to(ServerPlayer p, String msg) {
        Component chatComponent = Component.literal(msg);
        p.sendSystemMessage(chatComponent);
    }
    public void add_event(String a) {
        event_saturation = event_saturation_per_event;
        LOGGER.info("EVENT={}",a);
        if (!event_submission_list.isEmpty()) {
            var last = event_submission_list.get(event_submission_list.size()-1);
            if (last.getA().equals(a)) {
                last.setB(last.getB() + 1);
                event_submission_list.set(event_submission_list.size()-1, last);
                return;
            }
        }
        event_submission_list.add(new Tuple<>(a, 1));
    }
    public void submit() {
        if (
            (
                submission_cooldown_tick != 0
                || (event_saturation != 0 && event_staturated_ticks < event_staturated_ticks_threshold)
                )
                || (event_submission_list.size() == 0 && submission_persistence_tick != 0)
                || api_key.equals("null")
        ){
            return;
        }
        int nseconds_since = nticks_since_last_submit / nticks_per_second;
        int nminutes_since = nseconds_since / 60;
        nticks_since_last_submit = 0;
        submission_persistence_tick = ticks_per_persistence;
        submission_cooldown_tick = submission_cooldown;
        event_staturated_ticks = 0;
        StringBuilder new_events = new StringBuilder();
        if (nminutes_since != 0) {
            new_events.append(String.format("%d minutes and ", nminutes_since));
        }
        new_events.append(String.format("%d seconds have passed.\n", nseconds_since));
        if(event_submission_list.size() == 0) {
            new_events.append( "No events detected.\n");
        }
        for(var i = event_submission_list.size()-1; 0 <= i; i--) {
            var e = event_submission_list.get(i);
            String t = String.format("%s",e.getA());
            if (1 < e.getB()) {
                t += String.format(" %dx", e.getB());
            }
            t += '\n';
            if (new_events.length() + t.length() <= nmax_tokens) {
                new_events.insert(0, t);
            } else {
                break;
            }
        }
        new_events.insert(0, "New events:\n");
        event_submission_list.clear();

        ArrayList<OpenAI.Message> truncated_messages = new ArrayList<>();
        int truncated_len = 0;
        var ltm_msg = new OpenAI.Message();
        ltm_msg.role = "system";
        ltm_msg.content = String.join("\n", long_term_memories);
        truncated_messages.add(ltm_msg);
        truncated_len += ltm_msg.content.length();
        var tuning_msg = new OpenAI.Message();
        tuning_msg.role = "assistant";
        tuning_msg.content = """
            /think I have read the context and will now perform my duty.
            /say Hello world!""";
        truncated_len += tuning_msg.content.length();
        truncated_messages.add(tuning_msg);
        var new_event_msg = create_msg();
        new_event_msg.role = "system";
        new_event_msg.content = new_events.toString();
        new_event_msg.content += "Think first, then enter your commands:\n";
        for(int i = messages.size()-1; 0 <= i; i--) {
            var imsg = messages.get(i);
            if (nmax_tokens < truncated_len + imsg.content.length()) {
                break;
            }
            truncated_len += imsg.content.length();
            truncated_messages.add(2, imsg);
        }
        String url = "https://api.openai.com/v1/chat/completions";
        var requestBody = new HashMap<String, Object>();
        requestBody.put("model", "gpt-3.5-turbo");
        requestBody.put("messages", truncated_messages);
        new Thread( () -> {
            String response = null;
            var request_str = GSON.toJson(requestBody);
            LOGGER.info("<prompt>");
            for(var i : truncated_messages) {
                LOGGER.info("sent msg={}:\n{}", i.role, i.content);
            }
            LOGGER.info("</prompt>");
            try {
                response = OpenAI.makeOpenAIRequest(url, api_key, request_str);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            var completion =  GSON.fromJson(response, OpenAI.Completion.class);
            if (completion.choices == null) {
                LOGGER.error("completion.id="+completion.id);
            }
            var msg = completion.choices[0].message.content;
            synchronized (queued_msgs) {
                queued_msgs.add(msg);
            }
        }).start();

    }
    String api_key = "null";
    String common_commands = """
        Common commands:
        /smite player #Casts deadly lightning and fire on the player.
        /give player item amount
        /inspect player
        /players #List connected players.
        /say text
        /think text
        /tell player text #Privately message a player.
        In addition to these, all of Minecraft's commands are available.
        """;
    List<String> long_term_memories = new ArrayList<>(Arrays.asList("You are the god of ethics and democracy on a Minecraft server.",
            "You must ensure that you are the authority of morality, democracy, and rationality.",
            "You gather and analyze info before concluding anything.",
            "You happen to be passive aggressive, and make snarky remarks.",
            common_commands));
    public Instance(ServerStartingEvent event) {
        messages = new ArrayList<>();
        this.server = event.getServer();
    }
    public void try_add_entity_verb(Entity e0, String v, Entity e1) {
        var hurtCreature = e1;
        var aggressor = e0;
        if (aggressor instanceof Player) {
            var weapon = ((Player)aggressor).getItemInHand(InteractionHand.MAIN_HAND).getHoverName().getString();
            if (weapon.equalsIgnoreCase("air")) {
                weapon = "fist";
            }
            add_event(String.format("%s %s %s with %s",
                    aggressor.getName().getString(),
                    v,
                    hurtCreature.getName().getString(),
                    weapon
            ));
        } else if (hurtCreature instanceof Player) {
            if (aggressor != null) {
                add_event(String.format("%s %s %s",
                        aggressor.getName().getString(),
                        v,
                        hurtCreature.getName().getString()
                ));
            } else {
                add_event(String.format("%s was %s",
                    hurtCreature.getName().getString(),
                    v
                ));
            }
        }
    }
    @SubscribeEvent
    public void onEntityDamaged(LivingHurtEvent event) {
        try_add_entity_verb( event.getSource().getEntity(),"damaged",event.getEntity());
    }
    @SubscribeEvent
    public void onLivingDeathEvent(LivingDeathEvent event) {
        if (event.getSource().getEntity() instanceof Player) {
            try_add_entity_verb( event.getSource().getEntity(),"killed",event.getEntity());
            return;
        }
        if (event.getEntity() instanceof Player){
            add_event(String.format("%s died", event.getEntity().getDisplayName().getString()));
        }
    }
    @SubscribeEvent
    public void onPlayerChangeGameModeEvent(PlayerEvent.PlayerChangeGameModeEvent event) {
        add_event( String.format("%s changed game mode to %s",
                event.getEntity().getDisplayName().getString(), event.getNewGameMode().getName()));
    }
    @SubscribeEvent
    public void onPlayerLoggedInEvent(PlayerEvent.PlayerLoggedInEvent event) {
        add_event( String.format("%s logged in",
                event.getEntity().getDisplayName().getString()));
    }
    @SubscribeEvent
    public void onBreakEvent(BlockEvent.BreakEvent event) {

        add_event( String.format("%s broke a %s block",
                event.getPlayer().getDisplayName().getString(),
                event.getState().getBlock().getName().getString()
        ));
    }
    @SubscribeEvent
    public void onEntityPlaceEvent(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        add_event( String.format("%s placed a %s block",
                event.getEntity().getDisplayName().getString(),
                event.getState().getBlock().getName().getString()
        ));
    }
    @SubscribeEvent
    public void onPlayerLoggedOutEvent(PlayerEvent.PlayerLoggedOutEvent event) {
        add_event( String.format("%s logged out",
                event.getEntity().getDisplayName().getString()));
    }
    @SubscribeEvent
    public void onPlayerRespawnEvent(PlayerEvent.PlayerRespawnEvent event) {
        add_event( String.format("%s respawned",
                event.getEntity().getDisplayName().getString()));
    }
    @SubscribeEvent
    public void onServerChatEvent(ServerChatEvent.Submitted event) {
        var s = event.getPlayer().getDisplayName().getString() + " says \"" + event.getRawText() + "\"";
        add_event(s);
    }
    @SubscribeEvent
    public void onPlayerBrewedPotionEvent(PlayerBrewedPotionEvent event) {
        add_event( String.format("%s brewed %s",
                event.getEntity().getDisplayName().getString()
                ,event.getStack().getHoverName().getString()
        ));
    }
    @SubscribeEvent
    public void onFarmlandTrampleEvent(BlockEvent.FarmlandTrampleEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        add_event( String.format("%s trampled %s",
                event.getEntity().getDisplayName().getString()
                ,event.getState().getBlock().getName().getString()
        ));
    }
    @SubscribeEvent
    public void onLivingFallEvent(LivingFallEvent event) {
        if (!(event.getEntity() instanceof Player)
            || event.getDistance() < 3.0f
        ) {
            return;
        }
        add_event( String.format("%s fell from %.1f blocks",
                event.getEntity().getDisplayName().getString()
                ,event.getDistance()
        ));
    }
    @SubscribeEvent
    public void onItemPickupEvent(PlayerEvent.ItemPickupEvent event) {
        add_event( String.format("%s picked up %s %dx",
                event.getEntity().getDisplayName().getString()
                ,event.getStack().getHoverName().getString()
                ,event.getStack().getCount()
        ));
    }
    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        add_event( String.format("%s used %s",
                event.getEntity().getDisplayName().getString()
                ,event.getItemStack().getHoverName().getString()
        ));
    }
    @SubscribeEvent
    public void onPlayerContainerEventOpen(PlayerContainerEvent.Open event) {
    }
    @SubscribeEvent
    public void onCommandEvent(CommandEvent event) {
        var s = event.getParseResults().getContext().getSource();
        var e = s.getEntity();
        if (e instanceof Player) {
//            add_event( String.format("%s did command %s", e.getDisplayName().getString(),
//                    event.getParseResults().getContext().getCommand().toString()));
        }
    }
    @SubscribeEvent
    public void onServerTickEvent(TickEvent.ServerTickEvent event) {
        nticks_since_last_submit++;
        submission_cooldown_tick--;
        submission_cooldown_tick = Math.max(submission_cooldown_tick, 0);
        submission_persistence_tick--;
        submission_persistence_tick = Math.max(submission_persistence_tick, 0);
        event_saturation--;
        event_saturation = Math.max(event_saturation, 0);
        if (0 < event_saturation) {
            event_staturated_ticks++;
        } else {
            event_staturated_ticks = 0;
        }
        submit();
        ArrayList<String> q;
        synchronized (queued_msgs) {
            q = new ArrayList<>(queued_msgs);
            queued_msgs.clear();
        }
        for(var msg : q) {
            var msg1 = new OpenAI.Message();
            msg1.role = "assistant";
            msg1.content = msg;
            LOGGER.info("gptmsg={}", msg);
            messages.add(msg1);
            var lines = msg.split("\n");
            for (var line : lines) {
                line = line.strip();
                if (line.isEmpty()) continue;
                if (!line.startsWith("/")) {
//                    line = "/say " + line;
                    continue;
                }
                line = line.substring(1);
                var ss = server.createCommandSourceStack();
                if (line.startsWith("say ")) {
                    line = line.substring(4);
                    Component chatComponent = Component.literal("<God> " + line);
                    for (var player : server.getPlayerList().getPlayers()) {
                        player.sendSystemMessage(chatComponent);
                    }
                    continue;
                }
                var tokens = line.split(" ");
                if(line.startsWith("smite")) {
                    line = String.format("execute at %s run summon lightning_bolt", tokens[1]);
                } else if (line.startsWith("think")) {
                    for (var pname : mindread_subscriptions) {
                        var p = server.getPlayerList().getPlayerByName(pname);
                        if (p == null) continue;
                        send_msg_to(p, String.format("God thinks, \"%s\"", line.substring(5)));
                    }
                    continue;
                } else if (line.startsWith("inspect")) {
                    var player = server.getPlayerList().getPlayerByName(tokens[1]);
                    if (player == null) {
                        add_event(String.format("No player named %s.", tokens[1]));
                        continue;
                    }
                    var info = String.format("%s's info:", player.getDisplayName().getString());
                    info += String.format("\ninventory: ");
                    for (var item : player.getInventory().items) {
                        if (item.isEmpty())
                            continue;
                        info += String.format("%s %dx, " ,item.getHoverName().getString(), item.getCount());
                    }
                    info += String.format("\narmor: ");
                    for (var item : player.getInventory().armor) {
                        if (item.isEmpty())
                            continue;
                        info += String.format("%s %dx, " ,item.getHoverName().getString(), item.getCount());
                    }
                    info += String.format("\noffhand: ");
                    for (var item : player.getInventory().offhand) {
                        if (item.isEmpty())
                            continue;
                        info += String.format("%s %dx, " ,item.getHoverName().getString(), item.getCount());
                    }
                    info += String.format("\nmainhand: ");
                    info += String.format("%s %dx, "
                            , player.getInventory().getSelected().getHoverName().getString()
                            ,  player.getInventory().getSelected().getCount());
                    info += String.format("\nhealth: %d", (int)player.getHealth());
                    info += String.format("\nfood level: %d", player.getFoodData().getFoodLevel());
                    info += String.format("\ngame mode: %s", player.gameMode.getGameModeForPlayer().getName() );
                    info += String.format("\ndimension: %s", player.level.dimension().location());
                    var permissions = "guest";
                    if (player.hasPermissions(1)) {
                        permissions = "game master";
                    }
                    else if (player.hasPermissions(2)) {
                        permissions = "admin";
                    }
                    else if (player.hasPermissions(3)) {
                        permissions = "owner";
                    }
                    info += String.format("\npermissions: %s", permissions);
                    add_event(info);
                    continue;
                }
                else if (line.startsWith("players")) {
                    var players = server.getPlayerList().getPlayers();
                    StringBuilder info = new StringBuilder(String.format("%d players are connected:", players.size()));
                    for(var i : players){
                        info.append("\n").append(i.getDisplayName().getString());
                    }
                    add_event(info.toString());
                    continue;
                }
                try {
                    LOGGER.info("try command={}", line);
                    server.getCommands().getDispatcher().execute(line, ss.withSuppressedOutput());
                    ninvalid_commands_left = ninvalid_commands_threshold;
                } catch (CommandSyntaxException e) {
                    if (0 < ninvalid_commands_left) {
                        add_event(String.format("""
                            God's command was unknown or incomplete."""));
                        ninvalid_commands_left -= 1;
                        ninvalid_commands_left = Math.max(ninvalid_commands_left, 0);
                    }
                }
            }
        }
    }
}