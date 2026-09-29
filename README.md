# KiraziumDungeons

Kirazium icin Dungeons+ 4.1.10 entegrasyon katmani.

## 1.1.3 - Exact Nexo Items

Bu surumun ana amaci ItemEdit ile degistirilmis Nexo itemlerinin Dungeons loot sisteminde bozulmasini engellemektir.

- Nexo item Dungeons LootTable editorune eklenirken ID-only `NexoLoot` yerine tam `ItemStack` saklayan Dungeons `VanillaLoot/ItemValue` yoluna yonlendirilir.
- Tam ItemStack; display name, lore, enchant, attributes, item components ve PDC/NBT benzeri plugin verilerini korur.
- LootChest ve dungeon mob droplari ayni `LootTableConfiguration.generate()` yolunu kullandigi icin tek exact kayit iki yolu da kapsar.
- Kaydetmeden once Bukkit YAML serialize -> deserialize round-trip yapilir ve `ItemStack.equals()` ile birebir dogrulanir.
- Dogrulama gecmezse fail-closed davranilir: item bozuk sekilde kaydedilmez ve Dungeons'in ID-only NexoLoot yolu o tiklama icin engellenir.
- Eski NexoLoot girdileri otomatik migrate edilmez. Daha once kaybolmus ItemEdit metadata'si geri uretilemez; exact koruma icin item 1.1.3 ile loot table'a yeniden eklenmelidir.

## Phoenix key guvenligi

Legacy Nexo-proxy Phoenix key girdileri `PhoenixKeyBridge` ile duzeltilmeye devam eder. Ancak item zaten PhoenixCrates'in gercek `phoenixcrates:key` tag'ini tasiyorsa bridge iteme dokunmaz. Boylece ItemEdit ile ozellestirilmis gercek Phoenix key de korunur. Phoenix tag okuyucusu kullanilamazsa donusum veri kaybi riskine karsi fail-closed durur.

## Mevcut sistemler

- StageMonitor (0.2.1-beta davranisi binary olarak korunur)
- Exact Nexo Item Protection
- Phoenix physical key bridge
- Phoenix LootChest visual/model bridge
- Dungeon mob + LootChest glow

Dungeon Arrow sistemi 1.1.2 itibariyla tamamen kaldirilmistir ve geri eklenmemistir.
DeluxeMenus oyuncu ayarlari daha sonraki asamaya birakilmistir.

## Dogrulanan ortam

- Paper 26.1.2 build 74 stable
- Paper 26.2 build 129 stable API uyumlulugu
- Paper 26.3 guncel API uyumlulugu (pre-release/beta dahil)
- Dungeons+ 4.1.10
- Menus 1.9.2
- Nexo 1.28
- PhoenixCrates 6.0.0
- ModelEngine R4.1.0

## Komutlar

- `/kd status`
- `/kd reload`
- `/kd exact`
- `/kd keys`
- `/kd phoenix`
- `/kd glow`


## 1.1.4 Nexo GUI korumasi

- PhoenixKeyBridge artik `/nexo inv` ve Nexo'nun diger kendi GUI'lerinde hicbir itemi degistirmez.
- Nexo GUI tespiti title ile degil InventoryHolder class'i ile yapilir (`com.nexomc.libs.gui.*` / `com.nexomc.nexo.*`).
- Dungeons LootChest ve mob loot exact-item davranisi degismez.

## 1.1.5 Universal Paper 26.x

- Tek JAR icin desteklenen Minecraft/Paper surumleri: `26.1.2`, `26.2`, `26.3`.
- `api-version: 1.21` bilerek korunur; bu sayede 26.1.2 de yuklemeye devam eder.
- `/kd status` aktif Minecraft/Paper surumunu ve destek durumunu gosterir.
- 26.3 desteklenir; pre-release/beta Paper buildlerinde tam smoke-test onerilir.
- Bilinmeyen gelecekteki bir surumde plugin kendini kapatmaz; uyarir ve fail-open calismayi dener.
- Dungeons+ gereksinimi halen tam olarak `4.1.10`'dur.


## 1.1.6 Bossbar restore

- Varsayilan bossbar tekrar `Kirazium » Yaratık: X | Sandık: X/X` bicimindedir.
- `Alan/Spawner` gostergesi varsayilan bossbardan kaldirildi.
- Mob takip/stage degerlendirme mantigi degistirilmedi.
- 0.2.1, 1.0.0, 1.0.1, 1.1.1 ve 1.1.5 StageMonitor karsilastirmasinda takip mantigi aynidir.


## 1.1.7 Stage/Mob fix

- Restores `tracking.fall-failsafe.enabled: false` and the old `24.0` block threshold as defaults.
- Fixes the Paper 26.1.2+ `IncompatibleClassChangeError: Found interface org.bukkit.World, but class was expected` in the preserved StageMonitor binary.
- The failing world comparison in `shouldFailSafeRemove` is patched to reference-identity comparison, without changing the surrounding mob/stage evaluation logic.
- This allows the existing `mobs_remaining` evaluation and bossbar update path to complete again.
- Bossbar remains the old layout: `Kirazium » Yaratık: X | Sandık: X/X`.
