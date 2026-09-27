KiraziumDungeons 1.1.3 - EXACT ITEMS

Hedef ortam:
- Paper 26.1.2 build 74 stable
- Dungeons+ 4.1.10
- Menus 1.9.2
- Nexo 1.28
- PhoenixCrates 6.0.0
- ModelEngine R4.1.0

1.1.3:
- Dungeon Arrow tamamen kaldirildi.
- ItemEdit ile degistirilmis Nexo itemleri Dungeons LootTable editorunde NexoLoot (ID-only) yerine tam ItemStack saklayan VanillaLoot olarak kaydedilir.
- Ayni Dungeons LootTable generate yolu kullanildigi icin exact item hem LootChest odullerinde hem dungeon mob droplarinda korunur.
- Kaydetmeden once Bukkit YAML round-trip + ItemStack.equals dogrulamasi yapilir. Dogrulama gecmezse item fail-closed olarak loot'a eklenmez.
- Eski NexoLoot kayitlari otomatik migrate edilmez; daha once kaybolmus ItemEdit metadata'si guvenli sekilde geri uretilemez. Exact koruma icin itemi 1.1.3 ile loot table'a yeniden ekleyin.
- PhoenixKeyBridge legacy ID-only key'leri duzeltmeye devam eder; ancak itemde Phoenix'in gercek `phoenixcrates:key` tag'i varsa ItemEdit degisikliklerini korumak icin iteme dokunmaz.
- Phoenix key tag okuyucusu yuklenemezse veri kaybi riski almamak icin legacy key donusumu fail-closed durur.

Komutlar:
/kd status
/kd reload
/kd exact
/kd keys
/kd phoenix
/kd glow

DeluxeMenus oyuncu ayarlari bu surume dahil degildir.