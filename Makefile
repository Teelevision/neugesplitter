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

.PHONY: codec
codec: ## Run the :format tests and the format-spec vectors
	$(GRADLE) :format:test

# Colima and Docker Desktop keep their socket elsewhere; the containers still see it at the usual place.
.PHONY: integration
integration: ## Run the backend suite against SeaweedFS in Docker (TR-48)
	DOCKER_HOST=$${DOCKER_HOST:-$$(docker context inspect -f '{{.Endpoints.docker.Host}}')} \
	TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
	$(GRADLE) :backend-s3:integrationTest

S3_IP = $$(ipconfig getifaddr en0)

.PHONY: s3
s3: ## Serve SeaweedFS on this Mac's Wi-Fi address for a phone, and print its settings
	docker run -d --name neugesplitter-s3 --restart unless-stopped -p 8333:8333 \
		-v neugesplitter-s3:/data -v $(CURDIR)/backend-s3/seaweedfs.json:/etc/seaweedfs/s3.json:ro \
		chrislusf/seaweedfs:4.48 mini -dir=/data -s3.config=/etc/seaweedfs/s3.json -s3.autoCreateBucket=false
	until curl -sf localhost:8333/healthz > /dev/null; do sleep 1; done
	for b in groups spare; do echo "s3.bucket.create -name $$b" | docker exec -i neugesplitter-s3 weed shell -master=localhost:9333 > /dev/null; done
	@printf 'endpoint: http://%s:8333\nbucket: groups\nregion: us-east-1\npath_style: true\naccess_key: writer\nsecret_key: writer-secret\n' "$(S3_IP)"

.PHONY: s3-stop
s3-stop: ## Remove the SeaweedFS container; its data stays in the neugesplitter-s3 volume
	docker rm -f neugesplitter-s3
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

.PHONY: install-second
install-second: ## Install a second debug app beside the first, to sync between two on one phone
	$(GRADLE) installDebug -Psecond

.PHONY: screenshots
screenshots: ## Record the screens in German at three text sizes, into app/build/outputs/roborazzi
	$(GRADLE) :app:testDebugUnitTest --tests '*.ScreenshotTest' -Pscreenshots --rerun

REPRO := build/reproducible
APK := app/build/outputs/apk/release/app-release-unsigned.apk

# Two clones at different depths, so an absolute path that leaks into the apk shows up.
.PHONY: reproducible
reproducible: ## Build the release apk from two clean clones of HEAD and compare them byte for byte (TR-51)
	rm -rf $(REPRO) && mkdir -p $(REPRO)/elsewhere
	git clone -q --no-local . $(REPRO)/one
	git clone -q --no-local . $(REPRO)/elsewhere/two
	for d in one elsewhere/two; do \
		(cd $(REPRO)/$$d && ANDROID_HOME=$${ANDROID_HOME:-$$(sed -n 's/^sdk.dir=//p' $(CURDIR)/local.properties)} \
			./gradlew -q --no-build-cache $(GRADLE_ARGS) :app:assembleRelease) || exit 1; \
	done
	shasum -a 256 $(REPRO)/one/$(APK) $(REPRO)/elsewhere/two/$(APK)
	@cmp -s $(REPRO)/one/$(APK) $(REPRO)/elsewhere/two/$(APK) || { \
		unzip -v $(REPRO)/one/$(APK) > $(REPRO)/one.txt; unzip -v $(REPRO)/elsewhere/two/$(APK) > $(REPRO)/two.txt; \
		diff $(REPRO)/one.txt $(REPRO)/two.txt; echo "The two builds differ."; exit 1; }
	@echo "The two builds are identical."

.PHONY: report
report: ## Open the last test report
	open domain/build/reports/tests/test/index.html

.PHONY: clean
clean: ## Delete the build outputs
	$(GRADLE) clean
