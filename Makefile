.PHONY: build test run run-verbose validate visualize visualize-mermaid clean dev help

# Default workflow file
WORKFLOW ?= working-dir/workflows/georgia-discovery.kt

help:
	@echo "Hensu - AI Workflow Engine"
	@echo ""
	@echo "Usage:"
	@echo "  make build             Build all modules"
	@echo "  make test              Run all tests"
	@echo "  make run               Run workflow"
	@echo "  make run-verbose       Run workflow with agent I/O output"
	@echo "  make validate          Validate workflow syntax"
	@echo "  make visualize         Visualize as text (ASCII)"
	@echo "  make visualize-mermaid Visualize as Mermaid diagram"
	@echo "  make dev               Run CLI in Quarkus dev mode"
	@echo "  make clean             Clean build artifacts"
	@echo ""
	@echo "Examples:"
	@echo "  make run                                                                   # Run default workflow"
	@echo "  make run-verbose                                                           # Run with agent I/O shown"
	@echo "  make run WORKING_DIR=my-working-dir WORKFLOW=my-workflow.kt                # Run specific workflow"
	@echo "  make validate WORKING_DIR=my-working-dir WORKFLOW=my-workflow.kt"
	@echo "  make visualize-mermaid WORKING_DIR=my-working-dir WORKFLOW=my-workflow.kt"
	@echo ""
	@echo "Or use the ./hensu script directly:"
	@echo "  ./hensu run -d working-dir workflow.kt"
	@echo "  ./hensu run -d working-dir workflow.kt -v"
	@echo "  ./hensu validate -d working-dir workflow.kt"
	@echo "  ./hensu visualize -d working-dir workflow.kt --format=mermaid"

build:
	./gradlew build -x test

test:
	./gradlew test

run: build
	./hensu run -d $(WORKING_DIR) $(WORKFLOW)

run-verbose: build
	./hensu run -d $(WORKING_DIR) $(WORKFLOW) --verbose

validate: build
	./hensu validate -d $(WORKING_DIR) $(WORKFLOW)

visualize: build
	./hensu visualize -d $(WORKING_DIR) $(WORKFLOW) --format=text

visualize-mermaid: build
	./hensu visualize -d $(WORKING_DIR) $(WORKFLOW) --format=mermaid

dev:
	./gradlew hensu-cli:quarkusDev

clean:
	./gradlew clean