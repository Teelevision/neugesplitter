GRADLE := ./gradlew

.DEFAULT_GOAL := help

.PHONY: help
help: ## List the targets
	@grep -hE '^[a-z-]+:.*?## ' $(MAKEFILE_LIST) | sort | \
		awk -F':.*?## ' '{printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

.PHONY: check
check: ## Format, then run everything CI runs (development.md 3)
	$(GRADLE) spotlessApply check

.PHONY: test
test: ## Run the JVM tests
	$(GRADLE) test

.PHONY: domain
domain: ## Run the domain tests alone, which is the fast loop
	$(GRADLE) :domain:test

.PHONY: format
format: ## Apply ktfmt and prettier
	$(GRADLE) spotlessApply

.PHONY: lint
lint: ## Android Lint and the Spotless check, without the tests
	$(GRADLE) spotlessCheck lint

.PHONY: build
build: ## Assemble the debug apk
	$(GRADLE) assembleDebug

.PHONY: install
install: ## Install the debug apk on the connected device
	$(GRADLE) installDebug

.PHONY: report
report: ## Open the last test report
	open domain/build/reports/tests/test/index.html

.PHONY: clean
clean: ## Delete the build outputs
	$(GRADLE) clean
