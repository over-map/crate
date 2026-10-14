# Routine tasks. Run `make help`. Local commands read secrets from your environment (see .env.example).
PIPE = cd pipeline && uv run

.PHONY: help setup test lint sync backfill analyse vectors report query-dry weekly health contract migrate-dry ios-test ios-gen ios-demo ios-sim ios-build

help:
	@grep -E '^[a-z-]+:.*## ' Makefile | sed 's/:.*## /\t/' | sort

setup: ## install Python deps incl. Essentia (macOS arm64 and Linux x86_64 wheels)
	cd pipeline && uv sync --extra audio

test: ## Python + Swift tests (both run the shared query vectors)
	cd pipeline && uv run ruff check crate tests && uv run pytest -q
	cd ios/CrateKit && swift test

lint: ## ruff
	cd pipeline && uv run ruff check crate tests && uv run ruff format --check crate tests

sync: ## Liked Songs -> data/library.json
	$(PIPE) crate sync

backfill: ## analyse every pending track (no cap), recompute energy, fit vectors
	$(PIPE) crate sync
	$(PIPE) crate analyse
	$(PIPE) crate vectors --fit

analyse: ## analyse up to 200 pending tracks (M1)
	$(PIPE) crate analyse --max 200

vectors: ## export similarity vectors with the existing PCA basis
	$(PIPE) crate vectors

report: ## write docs/tag-quality-report.md
	$(PIPE) crate report

query-dry: ## e.g. make query-dry Q='{"genres_include":["Electronic"]}'
	$(PIPE) crate query --json '$(Q)' --dry-run

weekly: ## build the Weekly Mix now
	$(PIPE) crate weekly

health: ## health check (writes data/health.json)
	$(PIPE) crate health

contract: ## upstream API contract check
	$(PIPE) crate contract

migrate-dry: ## playlist migration dry run (backup + plan + confirmation code)
	$(PIPE) crate migrate

ios-test: ## CrateKit tests
	cd ios/CrateKit && swift test

ios-gen: ## regenerate design tokens and the Xcode project (after a new design export or target change)
	python3 ios/tools/gen_tokens.py && python3 ios/tools/gen_xcodeproj.py

ios-demo: ## copy data/ into the Debug app as demo data (git-ignored; lets the Simulator run without logging in)
	mkdir -p ios/Crate/Demo && for f in library overrides presets vibes taxonomy weekly state health vectors.idx; do cp data/$$f.json ios/Crate/Demo/demo-$$f.json; done && cp data/vectors.bin ios/Crate/Demo/demo-vectors.bin

ios-sim: ## build the app for the iOS Simulator (no signing)
	cd ios && xcodebuild -project Crate.xcodeproj -scheme Crate -destination 'generic/platform=iOS Simulator' -derivedDataPath ../.cache/DerivedData CODE_SIGNING_ALLOWED=NO build

ios-build: ## build for your iPhone (set your Team in Xcode once: Signing & Capabilities)
	cd ios && xcodebuild -project Crate.xcodeproj -scheme Crate -configuration Release -destination generic/platform=iOS -allowProvisioningUpdates build
