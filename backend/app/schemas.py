"""Strict transport models; domain modules enforce reference/business rules."""
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, RootModel


class Input(BaseModel):
    model_config = ConfigDict(strict=True, extra="ignore")

    def data(self):
        return self.model_dump(exclude_unset=True)


class ObjectResponse(RootModel[dict[str, Any]]):
    pass


class ResetRequest(Input):
    seed: int = 42
    stock: dict[str, int] = Field(default_factory=dict)


class SportRequest(Input):
    sport_id: str
    name: str
    scoring_model: str
    periods: dict[str, Any]
    scoring_events: dict[str, Any]
    draw_allowed: bool
    overtime: dict[str, Any]
    roster: dict[str, Any]
    tie_break_order: list[str] = Field(default_factory=list)


class FixturesRequest(Input):
    format: str
    teams: list[str]
    params: dict[str, Any] = Field(default_factory=dict)


class LeagueRequest(Input):
    league_id: str
    sport_id: str
    teams: list[str]
    fixtures: list[dict[str, Any]]
    qualification: dict[str, Any]
    points: dict[str, int] = Field(default_factory=dict)
    tie_break_order: list[str] = Field(default_factory=list)
    lots_seed: int = 42
    name: str | None = None
    tenant_id: str = "tenant-alpha"


class EventRequest(Input):
    event_id: str
    match_id: str
    type: str
    payload: dict[str, Any]


class RosterSetupRequest(Input):
    league: dict[str, Any]
    squads: list[dict[str, Any]]
    players: list[dict[str, Any]]
    matches_in_progress: list[dict[str, Any]] = Field(default_factory=list)


class TransferRequest(Input):
    request_id: str
    league_id: str
    player_id: str
    type: str
    from_squad_id: str
    to_squad_id: str | None = None
    effective_date: str
    loan_end_date: str | None = None
    reason: str
    requested_by: str


class CheckoutItem(Input):
    variant_id: str
    quantity: int


class CheckoutRequest(Input):
    order_id: str
    customer_id: str
    items: list[CheckoutItem]
    discount_code: str | None = None
    payment: dict[str, Any]


class OfflineRequest(Input):
    device_id: str
    actions: list[dict[str, Any]]


class LoginRequest(Input):
    email: str
    password: str


class PinRequest(Input):
    product_id: str | None


class OrderStatusRequest(Input):
    request_id: str
    status: str


class LeagueConfigurationRequest(Input):
    minimum_rest_minutes: int = 0
    season: str | None = None
    format: str | None = None
    divisions: list[dict[str, Any]] = Field(default_factory=list)
    promotion_relegation: dict[str, Any] = Field(default_factory=dict)
    group_stage: dict[str, Any] = Field(default_factory=dict)


class TenantBrandingRequest(Input):
    name: str | None = None
    primary_color: str | None = None
    logo_url: str | None = None
