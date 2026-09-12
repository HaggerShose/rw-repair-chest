package de.mahagst.risingworld.repairchest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

/**
 * Operator config file ({@code settings.json}). SQLite keeps world state only.
 * A later OZ UI can write this same file and call {@link #load()}.
 */
public final class RepairSettingsStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path file;

	public RepairSettingsStore(String path) {
		this.file = Path.of(path);
	}

	/** Create from {@link RepairSettings#defaults()} when missing; corrupt files keep defaults in RAM. */
	public RepairSettings loadOrCreate() {
		if (!Files.exists(file)) {
			try {
				save(RepairSettings.defaults());
			} catch (IOException e) {
				System.out.println("[RepairChest] Could not write settings.json: " + e.getMessage());
				return RepairSettings.defaults();
			}
		}
		return load().orElseGet(() -> {
			System.out.println("[RepairChest] Invalid settings.json, using built-in defaults");
			return RepairSettings.defaults();
		});
	}

	public Optional<RepairSettings> load() {
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		try {
			return parse(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException e) {
			System.out.println("[RepairChest] Could not read settings.json: " + e.getMessage());
			return Optional.empty();
		}
	}

	public void save(RepairSettings settings) throws IOException {
		Path parent = file.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(FileDto.from(settings)), StandardCharsets.UTF_8);
		try {
			Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	static Optional<RepairSettings> parse(String json) {
		FileDto dto;
		try {
			dto = GSON.fromJson(json, FileDto.class);
		} catch (JsonSyntaxException e) {
			System.out.println("[RepairChest] settings.json is not valid JSON: " + e.getMessage());
			return Optional.empty();
		}
		if (dto == null) {
			return Optional.empty();
		}
		return dto.toSettings();
	}

	static final class FileDto {
		Float debounceSeconds;
		Float postTakeScanSeconds;
		Float repairSeconds;
		Float interactDistance;
		Integer fullPriceRemainingPercent;
		Integer goldFee;
		String goldItemName;
		List<FullPriceOnlyDto> fullPriceOnlyIngredients;
		List<ManualRecipeDto> manualRecipes;
		List<String> allowedChestTypes;
		List<WhitelistDto> whitelist;
		List<String> allowedUids;

		static FileDto from(RepairSettings settings) {
			var dto = new FileDto();
			dto.debounceSeconds = settings.debounceSeconds();
			dto.postTakeScanSeconds = settings.postTakeScanSeconds();
			dto.repairSeconds = settings.repairSeconds();
			dto.interactDistance = settings.interactDistance();
			dto.fullPriceRemainingPercent = settings.fullPriceRemainingPercent();
			dto.goldFee = settings.goldFee();
			dto.goldItemName = settings.goldItemName();
			dto.fullPriceOnlyIngredients = new ArrayList<>();
			for (var rule : settings.fullPriceOnlyIngredients()) {
				dto.fullPriceOnlyIngredients.add(new FullPriceOnlyDto(rule.ingredientName(),
						List.copyOf(rule.forTargets())));
			}
			dto.manualRecipes = new ArrayList<>();
			for (var recipe : settings.manualRecipes()) {
				var ingredients = new ArrayList<ManualIngredientDto>();
				for (var ingredient : recipe.ingredients()) {
					ingredients.add(new ManualIngredientDto(ingredient.itemName(), ingredient.count(),
							ingredient.consume()));
				}
				dto.manualRecipes.add(new ManualRecipeDto(recipe.targetName(), recipe.craftAmount(), ingredients));
			}
			dto.allowedChestTypes = List.copyOf(settings.allowedChestTypes());
			dto.whitelist = new ArrayList<>();
			for (var seed : settings.whitelistSeeds()) {
				dto.whitelist.add(new WhitelistDto(seed.name(), seed.fallbackTypeId()));
			}
			dto.allowedUids = List.copyOf(settings.allowedUids());
			return dto;
		}

		Optional<RepairSettings> toSettings() {
			RepairSettings defaults = RepairSettings.defaults();
			float debounce = debounceSeconds != null ? debounceSeconds : defaults.debounceSeconds();
			float postTake = postTakeScanSeconds != null ? postTakeScanSeconds : defaults.postTakeScanSeconds();
			float repair = repairSeconds != null ? repairSeconds : defaults.repairSeconds();
			float distance = interactDistance != null ? interactDistance : defaults.interactDistance();
			int percent = fullPriceRemainingPercent != null
					? fullPriceRemainingPercent
					: defaults.fullPriceRemainingPercent();
			int fee = goldFee != null ? goldFee : defaults.goldFee();
			String gold = goldItemName != null ? goldItemName.trim() : defaults.goldItemName();
			if (debounce < 0 || postTake < 0 || repair < 0 || distance < 0 || fee < 0) {
				System.out.println("[RepairChest] settings.json has negative timer, distance or gold fee");
				return Optional.empty();
			}
			if (percent < 0 || percent > 100) {
				System.out.println("[RepairChest] settings.json fullPriceRemainingPercent must be 0-100");
				return Optional.empty();
			}
			if (gold.isEmpty()) {
				System.out.println("[RepairChest] settings.json goldItemName is empty");
				return Optional.empty();
			}
			return Optional.of(new RepairSettings(
					debounce,
					postTake,
					repair,
					distance,
					percent,
					fee,
					gold,
					parseFullPriceOnly(defaults),
					parseManualRecipes(defaults),
					parseChestTypes(defaults),
					parseWhitelist(defaults),
					parseUids(defaults)));
		}

		private List<RepairSettings.FullPriceOnlyIngredient> parseFullPriceOnly(RepairSettings defaults) {
			if (fullPriceOnlyIngredients == null) {
				return defaults.fullPriceOnlyIngredients();
			}
			var rules = new ArrayList<RepairSettings.FullPriceOnlyIngredient>();
			for (FullPriceOnlyDto dto : fullPriceOnlyIngredients) {
				if (dto == null || dto.ingredientName == null || dto.ingredientName.isBlank()) {
					System.out.println("[RepairChest] Skipping fullPriceOnlyIngredients entry without ingredientName");
					continue;
				}
				var targets = new LinkedHashSet<String>();
				if (dto.forTargets != null) {
					for (String target : dto.forTargets) {
						if (target != null && !target.isBlank()) {
							targets.add(target.trim());
						}
					}
				}
				if (targets.isEmpty()) {
					System.out.println("[RepairChest] Skipping fullPriceOnlyIngredients entry without forTargets: "
							+ dto.ingredientName.trim());
					continue;
				}
				rules.add(new RepairSettings.FullPriceOnlyIngredient(dto.ingredientName.trim(), targets));
			}
			return rules;
		}

		private List<RepairSettings.ManualRecipe> parseManualRecipes(RepairSettings defaults) {
			if (manualRecipes == null) {
				return defaults.manualRecipes();
			}
			var recipes = new ArrayList<RepairSettings.ManualRecipe>();
			for (ManualRecipeDto dto : manualRecipes) {
				if (dto == null || dto.targetName == null || dto.targetName.isBlank()) {
					System.out.println("[RepairChest] Skipping manualRecipes entry without targetName");
					continue;
				}
				var ingredients = new ArrayList<RepairSettings.ManualIngredient>();
				if (dto.ingredients != null) {
					for (ManualIngredientDto ingredient : dto.ingredients) {
						if (ingredient == null || ingredient.itemName == null || ingredient.itemName.isBlank()
								|| ingredient.count == null || ingredient.count <= 0) {
							System.out.println("[RepairChest] Skipping manual recipe ingredient for "
									+ dto.targetName.trim());
							continue;
						}
						boolean consume = ingredient.consume == null || ingredient.consume;
						ingredients.add(new RepairSettings.ManualIngredient(
								ingredient.itemName.trim(), ingredient.count, consume));
					}
				}
				if (ingredients.isEmpty()) {
					System.out.println("[RepairChest] Skipping manualRecipes entry without ingredients: "
							+ dto.targetName.trim());
					continue;
				}
				int amount = dto.craftAmount != null && dto.craftAmount > 0 ? dto.craftAmount : 1;
				recipes.add(new RepairSettings.ManualRecipe(dto.targetName.trim(), amount, ingredients));
			}
			return recipes;
		}

		private Set<String> parseChestTypes(RepairSettings defaults) {
			if (allowedChestTypes == null) {
				return defaults.allowedChestTypes();
			}
			var types = new LinkedHashSet<String>();
			for (String type : allowedChestTypes) {
				if (type == null || type.isBlank()) {
					System.out.println("[RepairChest] Skipping blank allowedChestTypes entry");
					continue;
				}
				types.add(type.trim());
			}
			return types;
		}

		private List<RepairSettings.WhitelistSeed> parseWhitelist(RepairSettings defaults) {
			if (whitelist == null) {
				return defaults.whitelistSeeds();
			}
			var seeds = new ArrayList<RepairSettings.WhitelistSeed>();
			for (WhitelistDto dto : whitelist) {
				if (dto == null || dto.name == null || dto.name.isBlank()) {
					System.out.println("[RepairChest] Skipping whitelist entry without name");
					continue;
				}
				short fallback = dto.fallbackTypeId != null ? dto.fallbackTypeId : 0;
				seeds.add(new RepairSettings.WhitelistSeed(dto.name.trim(), fallback));
			}
			return seeds;
		}

		private Set<String> parseUids(RepairSettings defaults) {
			if (allowedUids == null) {
				return defaults.allowedUids();
			}
			var uids = new LinkedHashSet<String>();
			for (String uid : allowedUids) {
				if (uid == null || uid.isBlank()) {
					System.out.println("[RepairChest] Skipping blank allowedUids entry");
					continue;
				}
				uids.add(uid.trim());
			}
			return uids;
		}
	}

	static final class FullPriceOnlyDto {
		String ingredientName;
		List<String> forTargets;

		FullPriceOnlyDto() {
		}

		FullPriceOnlyDto(String ingredientName, List<String> forTargets) {
			this.ingredientName = ingredientName;
			this.forTargets = forTargets;
		}
	}

	static final class ManualRecipeDto {
		String targetName;
		Integer craftAmount;
		List<ManualIngredientDto> ingredients;

		ManualRecipeDto() {
		}

		ManualRecipeDto(String targetName, Integer craftAmount, List<ManualIngredientDto> ingredients) {
			this.targetName = targetName;
			this.craftAmount = craftAmount;
			this.ingredients = ingredients;
		}
	}

	static final class ManualIngredientDto {
		String itemName;
		Integer count;
		Boolean consume;

		ManualIngredientDto() {
		}

		ManualIngredientDto(String itemName, Integer count, Boolean consume) {
			this.itemName = itemName;
			this.count = count;
			this.consume = consume;
		}
	}

	static final class WhitelistDto {
		String name;
		Short fallbackTypeId;

		WhitelistDto() {
		}

		WhitelistDto(String name, Short fallbackTypeId) {
			this.name = name;
			this.fallbackTypeId = fallbackTypeId;
		}
	}
}
