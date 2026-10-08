PYTHON ?= python
LABELS ?= eval/food/labels.json
IMAGES ?= eval/food/images
LIMIT ?= 100
PROMPT ?= baseline-v1
SPLIT ?= development

.PHONY: eval eval-check test-ml

eval:
	$(PYTHON) ml-service/eval_food.py --labels "$(LABELS)" --images "$(IMAGES)" --limit $(LIMIT) --prompt-version $(PROMPT) --split $(SPLIT) --require-targets

eval-check:
	$(PYTHON) ml-service/eval_food.py --labels "$(LABELS)" --images "$(IMAGES)" --check

test-ml:
	$(PYTHON) -m pytest ml-service/tests -q
