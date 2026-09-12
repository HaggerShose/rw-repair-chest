package de.mahagst.risingworld.repairchest.oz;

import java.util.List;

import de.mahagst.risingworld.repairchest.RepairService;
import de.mahagst.risingworld.repairchest.RepairSettings;
import de.omegazirkel.risingworld.tools.ui.BasePlayerPluginSettingsPanel;
import de.omegazirkel.risingworld.tools.ui.ButtonFactory;
import de.omegazirkel.risingworld.tools.ui.OZUIElement;
import de.omegazirkel.risingworld.tools.ui.PlayerPluginSettings;
import de.omegazirkel.risingworld.tools.ui.PlayerPluginSettingsOverlay;
import de.omegazirkel.risingworld.tools.ui.table.TableCell;
import de.omegazirkel.risingworld.tools.ui.table.TableRow;
import de.omegazirkel.risingworld.tools.ui.table.TableScrollView;
import net.risingworld.api.assets.TextureAsset;
import net.risingworld.api.definitions.Definitions;
import net.risingworld.api.definitions.Items;
import net.risingworld.api.objects.Player;
import net.risingworld.api.ui.UIElement;
import net.risingworld.api.ui.UILabel;
import net.risingworld.api.ui.UIScrollView;
import net.risingworld.api.ui.UITarget;
import net.risingworld.api.ui.style.DisplayStyle;
import net.risingworld.api.ui.style.FlexDirection;
import net.risingworld.api.ui.style.ScaleMode;
import net.risingworld.api.ui.style.Unit;
import net.risingworld.api.utils.ItemData;

/**
 * Optional OZ Tools panel under Einstellungen. Loaded via reflection so the core
 * plugin stays usable without OZ on the server.
 */
public final class OzWhitelistUi extends PlayerPluginSettings {
	private static final String OZ_OVERLAY_ATTRIBUTE = "tools.ui.overlay";
	private static final List<String> COLUMNS = List.of("Icon", "Name", "Id", "Action");
	private static final List<Float> COLUMN_WIDTHS = List.of(10f, 37f, 28f, 25f);

	private static volatile boolean registered;

	private final RepairService service;
	private String lastStatus;

	private OzWhitelistUi(String pluginName, String version, RepairService service) {
		this.pluginLabel = pluginName;
		this.pluginVersion = version;
		this.service = service;
	}

	public static void register(String pluginName, String version, RepairService service) {
		if (registered) {
			return;
		}
		PlayerPluginSettingsOverlay.registerPlayerPluginSettings(new OzWhitelistUi(pluginName, version, service));
		registered = true;
		System.out.println("[RepairChest] OZ settings UI registered (Einstellungen tab)");
	}

	public static void unregister() {
		registered = false;
		System.out.println(
				"[RepairChest] OZ settings UI unregistered (OZ has no unregisterPlayerPluginSettings; entry may remain until server restart)");
	}

	@Override
	public BasePlayerPluginSettingsPanel createPlayerPluginSettingsUIElement(Player uiPlayer) {
		return new Panel(uiPlayer, this);
	}

	private static final class Panel extends BasePlayerPluginSettingsPanel {
		private final Player player;
		private final OzWhitelistUi ui;

		private Panel(Player player, OzWhitelistUi ui) {
			super(player, ui.pluginLabel);
			this.player = player;
			this.ui = ui;
		}

		@Override
		protected void redrawContent() {
			flexWrapper.removeAllChilds();
			hideHorizontalScroll(settingsContainer);
			if (!registered) {
				return;
			}
			OZUIElement body = fullWidthColumn();
			if (!ui.service.isAllowed(player)) {
				body.addChild(line("Admins only", 18f));
				flexWrapper.addChild(body);
				return;
			}
			body.addChild(line("Repairable items", 18f));
			if (ui.lastStatus != null && !ui.lastStatus.isBlank()) {
				body.addChild(line(ui.lastStatus, 14f));
			}
			body.addChild(buildTable());
			flexWrapper.addChild(body);
		}

		private TableScrollView buildTable() {
			var seeds = ui.service.settings().whitelistSeeds();
			TableScrollView table = new TableScrollView(COLUMNS, COLUMN_WIDTHS);
			table.style.width.set(100f, Unit.Percent);
			hideTableHorizontalScroll(table);
			float rows = seeds.size() + 1f;
			table.setScrollBodyHeight(Math.min(360f, Math.max(64f, rows * 34f)));
			for (RepairSettings.WhitelistSeed seed : seeds) {
				table.addRow(itemRow(seed));
			}
			table.addRow(addRow());
			return table;
		}

		private TableRow itemRow(RepairSettings.WhitelistSeed seed) {
			Items.ItemDefinition def = definitionOf(seed);
			String name = seed.name();
			OZUIElement remove = ButtonFactory.danger("Remove", event -> removeItem(name));
			remove.setSize(78f, 24f, false);
			return new TableRow(List.of(
					new TableCell(itemIcon(def), COLUMN_WIDTHS.get(0)),
					new TableCell(cellText(displayName(def, seed.name())), COLUMN_WIDTHS.get(1)),
					new TableCell(cellText(seed.name() + " (" + seed.fallbackTypeId() + ")"), COLUMN_WIDTHS.get(2)),
					new TableCell(remove, COLUMN_WIDTHS.get(3))));
		}

		private TableRow addRow() {
			OZUIElement add = ButtonFactory.ok("Add", event -> pickItem());
			add.setSize(78f, 24f, false);
			return new TableRow(List.of(
					new TableCell(null, COLUMN_WIDTHS.get(0)),
					new TableCell(cellText("Add item"), COLUMN_WIDTHS.get(1)),
					new TableCell(null, COLUMN_WIDTHS.get(2)),
					new TableCell(add, COLUMN_WIDTHS.get(3))));
		}

		private static void hideHorizontalScroll(UIScrollView scroll) {
			if (scroll != null) {
				scroll.setHorizontalScrollerVisibility(UIScrollView.ScrollerVisibility.Hidden);
			}
		}

		private static void hideTableHorizontalScroll(TableScrollView table) {
			try {
				var field = TableScrollView.class.getDeclaredField("scrollView");
				field.setAccessible(true);
				Object value = field.get(table);
				if (value instanceof UIScrollView scroll) {
					hideHorizontalScroll(scroll);
				}
			} catch (ReflectiveOperationException e) {
				System.out.println("[RepairChest] Could not hide table horizontal scrollbar: " + e.getMessage());
			}
		}

		private void pickItem() {
			if (!registered || !ui.service.isAllowed(player)) {
				return;
			}
			player.showItemSelectionMenu(true, false, false, false, this::onItemPicked);
		}

		private void onItemPicked(ItemData item) {
			if (!registered) {
				return;
			}
			if (item != null) {
				Items.ItemDefinition def = item.getItemDefinition();
				if (def == null || def.durability <= 0) {
					ui.lastStatus = "Item has no durability";
				} else {
					String err = ui.service.addRepairable(item.getName(), def.id);
					ui.lastStatus = err == null ? "Added " + displayName(def, item.getName()) : err;
				}
			}
			reopenSettings();
		}

		private void removeItem(String name) {
			if (!registered || !ui.service.isAllowed(player)) {
				return;
			}
			String err = ui.service.removeRepairable(name);
			ui.lastStatus = err == null ? "Removed " + name : err;
			redrawContent();
		}

		private void reopenSettings() {
			Object existing = player.getAttribute(OZ_OVERLAY_ATTRIBUTE);
			if (existing instanceof UIElement old) {
				player.removeUIElement(old);
			}
			PlayerPluginSettingsOverlay overlay = new PlayerPluginSettingsOverlay(player);
			selectPlugin(overlay, ui.pluginLabel);
			player.addUIElement(overlay, UITarget.Modal);
			player.setAttribute(OZ_OVERLAY_ATTRIBUTE, overlay);
		}

		private static void selectPlugin(PlayerPluginSettingsOverlay overlay, String pluginName) {
			try {
				var field = PlayerPluginSettingsOverlay.class.getDeclaredField("selectedPlugin");
				field.setAccessible(true);
				field.set(overlay, pluginName);
				overlay.updateUI();
			} catch (ReflectiveOperationException e) {
				System.out.println("[RepairChest] Could not restore RepairChest settings tab: " + e.getMessage());
			}
		}

		private String displayName(Items.ItemDefinition def, String fallback) {
			if (def == null) {
				return fallback;
			}
			String lang = player.getLanguage();
			if (lang == null || lang.isBlank()) {
				lang = player.getSystemLanguage();
			}
			String localized = lang == null ? null : def.getLocalizedName(lang);
			if (localized == null || localized.isBlank()) {
				return def.name != null ? def.name : fallback;
			}
			return localized;
		}

		private static Items.ItemDefinition definitionOf(RepairSettings.WhitelistSeed seed) {
			Items.ItemDefinition def = Definitions.getItemDefinition(seed.name());
			if (def != null) {
				return def;
			}
			return Definitions.getItemDefinition(seed.fallbackTypeId());
		}

		private static UIElement itemIcon(Items.ItemDefinition def) {
			UIElement icon = new UIElement();
			icon.style.width.set(24f, Unit.Pixel);
			icon.style.height.set(24f, Unit.Pixel);
			if (def != null) {
				TextureAsset texture = def.getIcon(0);
				if (texture != null) {
					icon.style.backgroundImage.set(texture);
					icon.style.backgroundImageScaleMode.set(ScaleMode.ScaleToFit);
				}
			}
			return icon;
		}

		private static UILabel cellText(String text) {
			UILabel label = new UILabel(text);
			label.setFontSize(13f);
			return label;
		}

		private static OZUIElement fullWidthColumn() {
			OZUIElement box = new OZUIElement();
			box.style.width.set(100f, Unit.Percent);
			box.style.display.set(DisplayStyle.Flex);
			box.style.flexDirection.set(FlexDirection.Column);
			return box;
		}

		private static UILabel line(String text, float size) {
			UILabel label = new UILabel(text);
			label.setFontSize(size);
			label.style.width.set(100f, Unit.Percent);
			label.style.minHeight.set(size + 6f, Unit.Pixel);
			return label;
		}
	}
}
