"""Offline, opt-in food-recording evaluation; never a clinical glucose predictor."""

import hashlib
import json
import unicodedata
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, HttpUrl, model_validator


def normalize_name(name: str) -> str:
    return "".join(unicodedata.normalize("NFKC", name).casefold().split())


class NutritionEntry(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    name: str = Field(min_length=1)
    printed_pages: str = Field(min_length=1)
    carbohydrate_g: float = Field(ge=0)
    sugars_g: float = Field(ge=0)
    fat_g: float = Field(ge=0)
    protein_g: float = Field(ge=0)


class NutritionReference(BaseModel):
    model_config = ConfigDict(extra="forbid")
    source_url: HttpUrl
    source_title: str = Field(min_length=1)
    basis_g: Literal[100]
    entries: list[NutritionEntry] = Field(min_length=1)
    limitation: str = Field(min_length=1)


class PolicyGroup(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: str = Field(min_length=1)
    names: list[str] = Field(min_length=1)
    parent_names: list[str] = Field(default_factory=list)
    basis: Literal["REFERENCE_COMPARISON", "IDENTITY_ONLY"]
    note: str = Field(min_length=1)
    nutrition_reference: NutritionReference | None = None


class ManagementPolicy(BaseModel):
    model_config = ConfigDict(extra="forbid")
    version: str = Field(min_length=1)
    scope: str = Field(min_length=1)
    groups: list[PolicyGroup] = Field(min_length=1)
    confusion_pairs: list[tuple[str, str]] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_groups(self):
        ids, names, parents = set(), set(), set()
        for group in self.groups:
            if group.id in ids:
                raise ValueError(f"중복 관리 그룹: {group.id}")
            ids.add(group.id)
            for name in group.names:
                key = normalize_name(name)
                if not key or key in names:
                    raise ValueError(f"중복 또는 빈 그룹 음식명: {name}")
                names.add(key)
            for name in group.parent_names:
                key = normalize_name(name)
                if not key:
                    raise ValueError("상위 음식명은 비어 있을 수 없습니다")
                parents.add(key)
            if group.basis == "REFERENCE_COMPARISON" and not group.nutrition_reference:
                raise ValueError(f"영양 비교 출처가 필요합니다: {group.id}")
            if group.nutrition_reference:
                entry_names = [normalize_name(entry.name) for entry in group.nutrition_reference.entries]
                if len(entry_names) != len(set(entry_names)) or set(entry_names) != {
                    normalize_name(name) for name in group.names
                }:
                    raise ValueError(f"영양 비교 그룹의 모든 음식에 중복 없는 출처 값이 필요합니다: {group.id}")
        if names & parents:
            raise ValueError("상위 이름을 확정 그룹 음식명으로 동시에 사용할 수 없습니다")
        for first, second in self.confusion_pairs:
            if first == second or first not in ids or second not in ids:
                raise ValueError("혼동 쌍은 서로 다른 기존 그룹이어야 합니다")
        return self

    def resolve(self, name: str) -> str | None:
        """Exact normalized catalog membership; never substring/tag/category inference."""
        key = normalize_name(name)
        for group in self.groups:
            if key in {normalize_name(value) for value in group.names}:
                return group.id
        return None

    def parent_matches(self, group_id: str, name: str) -> bool:
        key = normalize_name(name)
        return any(group.id == group_id and key in {
            normalize_name(value) for value in group.parent_names
        } for group in self.groups)

    def is_confusion(self, first: str, second: str | None) -> bool:
        return second is not None and any({first, second} == set(pair) for pair in self.confusion_pairs)


DEFAULT_POLICY = Path(__file__).with_name("food_management_policy.json")


def load_management_policy(path: Path = DEFAULT_POLICY) -> ManagementPolicy:
    return ManagementPolicy.model_validate(json.loads(path.read_text(encoding="utf-8-sig")))


def policy_evidence(policy: ManagementPolicy) -> dict:
    snapshot = policy.model_dump(mode="json")
    canonical = json.dumps(snapshot, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return {"version": policy.version, "sha256": hashlib.sha256(canonical.encode()).hexdigest(),
            "snapshot": snapshot, "status": "EXPERIMENTAL_NO_TARGET",
            "note": "기록용 분류 지표이며 의학적 혈당 예측 검증이 아님. 기존 완료 목표를 대체하지 않음."}
