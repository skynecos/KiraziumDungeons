# KiraziumDungeons

Kirazium sunucusu icin Dungeons+ 4.1.10 entegrasyon plugin'i.

## Mevcut sistemler

- StageMonitor: KiraziumDungeons 0.2.1-beta davranisi binary olarak korunur.
- PhoenixKeyBridge: Dungeons+ loot icindeki Nexo proxy key'i gercek PhoenixCrates fiziksel key ile degistirir.
- PhoenixLootChestBridge: Odul sistemine dokunmadan Dungeons+ LootChest'lere PhoenixCrates goruntu/animasyon katmani ekler.
- DungeonGlow: Dungeons+ `EntityController.entities` + session region eslesmesini kullanarak dungeon moblarini ve desteklenen Phoenix kasa modellerini parlatir.

## Iptal edilen sistem

Dungeon Arrow sistemi 1.1.2 ile tamamen kaldirildi. Glow sistemi son kalan moblari bulma ihtiyacini karsiladigi icin kaynak kod, komutlar ve config ayarlari artik Arrow icermiyor.

## Dogrulanan ortam

- Paper 26.1.2 build 74
- Dungeons+ 4.1.10
- PhoenixCrates 6.0.0
- Nexo 1.28
- ModelEngine R4.1.0

## Komutlar

- `/kd status`
- `/kd reload`
- `/kd keys`
- `/kd phoenix`
- `/kd glow`

DeluxeMenus oyuncu ayarlari entegrasyonu daha sonra eklenecek.

## Yol haritasi: Exact Nexo Item Protection

Sonraki surumun ana hedefi, ItemEdit ile degistirilmis Nexo itemlerinin Dungeons+ loot sisteminde bozulmadan korunmasidir.

Kabul kriterleri:
- LootChest odullerinde ItemEdit ile degistirilmis Nexo item birebir korunacak.
- Mob Loot Table / ground drop tarafinda ayni item birebir korunacak.
- Display name, lore, enchant, attributes, item components ve PDC/NBT benzeri plugin verileri kaybolmayacak.
- Dungeons sadece chance, minimum/maximum amount ve preferred slot gibi loot davranisini yonetecek; itemin kendisini Nexo ID'den sifirdan olusturmayacak.
- PhoenixCrates key bridge bu sistemle cakismayacak; gercek Phoenix key davranisi korunacak.
- Global ve dungeon asset loot table'larinin ikisi de kapsanacak.
- Arrow sistemi geri getirilmeyecek.
- DeluxeMenus oyuncu ayarlari daha sonraki asamada eklenecek.

Teknik yon:
Dungeons+ 4.1.10, Nexo itemleri varsayilan olarak Nexo ID uzerinden yeniden uretiyor. Exact koruma katmani ItemEdit'li Nexo itemleri tam ItemStack olarak saklayip generate sirasinda clone ederek geri vermelidir. Boylece ayni LootTable hem LootChest hem de mob droplarinda ayni exact itemi uretir.
