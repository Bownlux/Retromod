# ClientPlayNetworking v1 raw-bytes bridge: design notes

## Exact contracts (verified against real jars)

### OLD (≤1.20.1 fabric-api v1): channel + raw FriendlyByteBuf
- `ClientPlayNetworking.registerGlobalReceiver(ResourceLocation, PlayChannelHandler) -> boolean`
- `ClientPlayNetworking.registerReceiver(ResourceLocation, PlayChannelHandler) -> boolean`
- `ClientPlayNetworking.unregisterGlobalReceiver(ResourceLocation) -> PlayChannelHandler`
- `ClientPlayNetworking.send(ResourceLocation, FriendlyByteBuf)`
- `ClientPlayNetworking.getSender() -> PacketSender`
- `ClientPlayNetworking.canSend(ResourceLocation) -> boolean`
- `ClientPlayNetworking.getSendable() -> Set<ResourceLocation>`
- SAM `PlayChannelHandler.receive(Minecraft, ClientPacketListener, FriendlyByteBuf, PacketSender)`
  - intermediary: receive(Lnet/minecraft/class_310;Lnet/minecraft/class_634;Lnet/minecraft/class_2540;L.../PacketSender;)V

### NEW (26.1.2 fabric-api): typed CustomPacketPayload
- `ClientPlayNetworking.registerGlobalReceiver(CustomPacketPayload$Type, PlayPayloadHandler) -> boolean`
- `ClientPlayNetworking.send(CustomPacketPayload)`
- `ClientPlayNetworking.getSender() -> PacketSender`  (UNCHANGED, keep)
- SAM `PlayPayloadHandler.receive(CustomPacketPayload, Context)`
- `PayloadTypeRegistry.playC2S()/playS2C() -> PayloadTypeRegistry<RegistryFriendlyByteBuf>`
  - `.register(CustomPacketPayload$Type<T>, StreamCodec<? super RegistryFriendlyByteBuf, T>) -> Entry<T>`
- `Context.player()`, `Context.responseSender()` (PacketSender) for the receive callback
- MC types: CustomPacketPayload=class_8710, $Type=class_8710$class_9154, StreamCodec=class_9139,
  RegistryFriendlyByteBuf=class_9129, FriendlyByteBuf=class_2540, ResourceLocation=class_2960

## Bridge architecture

Synthetic classes in `com/retromod/generated/legacynet/`:

1. **RawPayload implements CustomPacketPayload** carries `ResourceLocation channelId` + `byte[] data`.
   - `type()` returns a `CustomPacketPayload$Type` built from channelId (cached per-id).
   - Needs a `Type` instance per channel id (Type wraps a ResourceLocation).

2. **RawPayloadCodec implements StreamCodec<RegistryFriendlyByteBuf, RawPayload>** for a given id.
   - encode: write the stored bytes into the buf.
   - decode: read all remaining readable bytes into byte[], wrap in RawPayload(id, bytes).

3. **ClientPlayNetworkingBridge** (static methods matching OLD signatures):
   - `registerGlobalReceiver(id, oldHandler)`:
       - lazily `PayloadTypeRegistry.playС2S().register(typeOf(id), codecOf(id))` AND playS2C (receive side is S2C)
       - `ClientPlayNetworking.registerGlobalReceiver(typeOf(id), newHandler)` where newHandler is a
         synthetic PlayPayloadHandler that, on receive(payload, ctx): builds a FriendlyByteBuf from
         payload.data, then calls oldHandler.receive(Minecraft.getInstance(), ctx.player().connection,
         buf, ctx.responseSender()).
   - `send(id, buf)`: read all bytes from buf, new RawPayload(id, bytes), ClientPlayNetworking.send(payload).
   - `getSender()`: delegate to ClientPlayNetworking.getSender().
   - `canSend(id)`: ClientPlayNetworking.canSend(typeOf(id)).
   - `getSendable()`: return empty set (or delegate).

4. Keep OLD `PlayChannelHandler` as a synthetic interface (its SAM is referenced by mod lambdas via
   invokedynamic, so the lambda's implemented interface must still exist with the SAM signature, and
   mods' `(mc, listener, buf, sender) -> {...}` lambdas continue to resolve). The bridge accepts this interface.

## Redirects
- class redirect: old PlayChannelHandler -> synthetic PlayChannelHandler (keep SAM identical).
- method redirects: ClientPlayNetworking.registerGlobalReceiver/registerReceiver/send/canSend/getSendable
  (the ResourceLocation-keyed overloads) -> ClientPlayNetworkingBridge static methods.
  getSender() is UNCHANGED in 26.1, so do NOT redirect it.

## VERIFIED intermediary signatures (from real 26.1.2 client jar + fabric-api 0.145.4)
- CustomPacketPayload = `class_8710`; its `type()` = **`method_56479()Lnet/minecraft/class_9154;`**
- CustomPacketPayload$Type = `class_8710$class_9154`; ctor = **`<init>(Lnet/minecraft/class_2960;)V`** (record wrapping a ResourceLocation)
- StreamCodec = `class_9139`; RegistryFriendlyByteBuf = `class_9129`; FriendlyByteBuf = `class_2540`
  - Still missing: StreamCodec's `encode` and `decode` SAM intermediary method names. `javap` was
    corruption-blocked this session. Mojang names are `decode(B)->V` and `encode(B,V)->void`;
    run `javap -p class_9139` from the real 26.1.2 jar to get the `method_*` names BEFORE codegen.
    A wrong name here = VerifyError at class load (worse than today's soft-fail). This is the gating unknown.
- Fabric PayloadTypeRegistry: statics `serverboundPlay()` / `clientboundPlay()` → `register(Type, StreamCodec)`
- Fabric ClientPlayNetworking: `registerGlobalReceiver(Type, PlayPayloadHandler)`; `send(CustomPacketPayload)`; `getSender()` UNCHANGED (do not redirect)
- Fabric PlayPayloadHandler SAM: `receive(T, Context)`; Context: `client()`, `player()`, `responseSender()`

## STATIC VERIFICATION (done: AppleSkin 1.17.1, transformed via CLI)
Transformed `appleskin-fabric-mc1.17.1-2.5.1.jar` (sole-blocked on PlayChannelHandler)
and disassembled `squeek/appleskin/network/ClientSyncHandler`:
- `registerGlobalReceiver(Identifier, PlayChannelHandler)` → `INVOKESTATIC
  ClientPlayNetworkingV1Bridge.registerGlobalReceiver(Object,Object)Z` ✓
- `getSender()` → left as `ClientPlayNetworking.getSender()` (unchanged in 26.1) ✓
- lambda `invokedynamic` → returns `LegacyPlayChannelHandler` (the kept synthetic SAM) ✓
- bootstrap samMethodType on the CLI path is intermediary `(class_310,class_634,class_2540,PacketSender)V`;
  the synthetic SAM is Mojang `(Minecraft,ClientPacketListener,FriendlyByteBuf,PacketSender)V`.
  These MATCH at runtime: on a 26.1 host the intermediary→Mojang class-redirects
  (class_310→Minecraft, …) are active, so ClassRemapper rewrites the bootstrap MethodType
  args to Mojang, converging with the synthetic interface. (CLI mismatch is benign: the
  audit checks class resolution only; real Fabric users get the runtime remap path.)
- This is why the synthetic SAM MUST be Mojang-typed (raw-injected, not re-remapped).

STILL UNVERIFIED (needs live launch): the reflective bridge BEHAVIOR, namely PayloadTypeRegistry
register success/timing, the Proxy codec encode/decode round-trip, and receive dispatch
into the old handler. Static analysis cannot exercise these.

## Implementation approach decision
Prefer **reflection** (extend the existing `embedded/NetworkingShim.java` scaffold) over raw ASM codegen
for the bridge logic. NetworkingShim already does reflective new-API discovery. The ONLY pieces that must
be synthetic bytecode are: (1) the kept `PlayChannelHandler` SAM interface (mod lambdas target it via
invokedynamic, so it must exist with the exact 4-arg `receive` descriptor), and (2) a `RawPayload`
implementing `CustomPacketPayload`. But even RawPayload can be a `java.lang.reflect.Proxy` over the
interface if `type()` returning a cached `Type` works through Proxy (verify: CustomPacketPayload is an
interface in 26.1 (yes), so Proxy is viable and avoids hand-writing a codec class in ASM).

## The hard parts / risks
- **Registration timing**: PayloadTypeRegistry.register MUST run before play phase. Old mods call
  registerGlobalReceiver in their ClientModInitializer, which is early enough. OK for the common case.
- **C2S vs S2C**: a client receiver listens to S2C; a client sender sends C2S. Must register BOTH the
  S2C codec (so inbound decodes) and C2S codec (so outbound encodes). Register both on first touch of id.
- **Writing real logic in raw ASM is huge & error-prone.** Better: write ClientPlayNetworkingBridge,
  RawPayload, RawPayloadCodec as REAL Java compiled against a generated MC stub, OR via reflection.
  MUST verify against an actual 26.1 launch; it can't be confidence-checked statically.
- Mods using the TYPED overload (registerGlobalReceiver(PacketType, PlayPacketHandler) + FabricPacket)
  are a SEPARATE removed API (FabricPacket), out of scope for THIS bridge; handle separately.
