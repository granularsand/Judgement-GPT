package com.granularsand.jgpt;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.network.chat.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import com.google.gson.Gson;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(JGPT.MODID)
public class JGPT {
    public static final String MODID = "jgpt";
    public static final Gson GSON = new Gson();
    private static final Logger LOGGER = LogUtils.getLogger();
    Instance instance;
    public JGPT() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }
    private void commonSetup(final FMLCommonSetupEvent event) {
    }
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        instance = new Instance(event);
        MinecraftForge.EVENT_BUS.register(instance);
    }

    @SubscribeEvent
    public void onRegisterCommandsEvent(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("jgpt_set_key").requires((p_138414_) -> {
            return p_138414_.hasPermission(2);
        }).then(Commands.argument("key", MessageArgument.message()).executes((p_214721_) -> {
            MessageArgument.ChatMessage messageargument$chatmessage = MessageArgument.getChatMessage(p_214721_, "key");
            instance.api_key = messageargument$chatmessage.signedArgument().signedContent().plain();
            return 1;
        })));
        event.getDispatcher().register(Commands.literal("jgpt_tell").requires((p_138414_) -> {
            return p_138414_.hasPermission(0);
        }).then(Commands.argument("message", MessageArgument.message()).executes((p_214721_) -> {
            MessageArgument.ChatMessage messageargument$chatmessage = MessageArgument.getChatMessage(p_214721_, "message");
            var msg = messageargument$chatmessage.signedArgument().signedContent().plain();
            instance.send_msg_to(p_214721_.getSource().getPlayerOrException(), String.format("Whisper sent to god: \"%s\"", msg));
            instance.add_event(String.format("%s whispers to you \"%s\"",
                    p_214721_.getSource().getPlayer().getDisplayName().getString(),
                    msg
            ));
            return 1;
        })
        ));
        event.getDispatcher().register(Commands.literal("jgpt_toggle_mindread").requires((p_138596_) -> {
            return p_138596_.hasPermission(0);
        }).executes((p_138593_) -> {
            var player = p_138593_.getSource().getPlayerOrException();
            var pname = player.getDisplayName().getString();
            Component chatComponent;
            if (instance.mindread_subscriptions.contains(pname)) {
                instance.mindread_subscriptions.remove(pname);
                chatComponent = Component.literal("mindread unsubscribed");
            } else {
                instance.mindread_subscriptions.add(pname);
                chatComponent = Component.literal("mindread subscribed");
            }
            player.sendSystemMessage(chatComponent);
            return 1;
        }));
        event.getDispatcher().register(Commands.literal("jgpt_long_term_memories").requires((p_138414_) -> {
            return p_138414_.hasPermission(0);
        }).executes((p_138593_) -> {
            var player = p_138593_.getSource().getPlayerOrException();
            for(int i = 0; i < instance.long_term_memories.size(); i++) {
                instance.send_msg_to(player, i + "=" + instance.long_term_memories.get(i));
            }
            return 1;
        }));
        event.getDispatcher().register(Commands.literal("jgpt_add_or_rm_long_term_memory").requires((p_138414_) -> {
            return p_138414_.hasPermission(2);
        }).then(Commands.argument("index", IntegerArgumentType.integer()).executes((p_214721_) -> {
            var i =  IntegerArgumentType.getInteger(p_214721_, "index");
            if (0 <= i && i < instance.long_term_memories.size()) {
                instance.long_term_memories.remove(i);
            }
            return 1;
        }).then(Commands.argument("text", StringArgumentType.string()).executes((p_214721_) -> {
            var i =  IntegerArgumentType.getInteger(p_214721_, "index");
            i = Math.max(0, i);
            i = Math.min(instance.long_term_memories.size(), i);
            var msg = StringArgumentType.getString(p_214721_, "text");
            instance.long_term_memories.add(i, msg);
            return 1;
        }))));
    }
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        MinecraftForge.EVENT_BUS.unregister(instance);
        instance = null;
    }
}
