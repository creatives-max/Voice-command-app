import { useDeferredValue, useState } from "react";
import { Link } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { Download, LayoutTemplate, Search, Star, Store, UserRound } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import type { MarketplaceParams } from "@/lib/api";
import { categoriesQuery, marketplaceQuery } from "@/lib/queries";
import type { Listing } from "@/lib/types";

type Tab = "community" | "templates" | "mine";

const TABS: { id: Tab; label: string; icon: typeof Store }[] = [
  { id: "community", label: "Community flows", icon: Store },
  { id: "templates", label: "Starter templates", icon: LayoutTemplate },
  { id: "mine", label: "My listings", icon: UserRound },
];

export const humanizeCategory = (c: string) => c.charAt(0).toUpperCase() + c.slice(1);

/** Browse and search flows others published, the starter templates, and your own listings. */
export function MarketplacePage() {
  const [tab, setTab] = useState<Tab>("community");
  const [q, setQ] = useState("");
  const [category, setCategory] = useState("");
  const [sort, setSort] = useState<NonNullable<MarketplaceParams["sort"]>>("popular");
  const query = useDeferredValue(q);
  const categories = useQuery(categoriesQuery);
  const params: MarketplaceParams = {
    q: query,
    category: category || undefined,
    templates: tab === "templates" ? true : tab === "community" ? false : undefined,
    mine: tab === "mine",
    sort: query.trim() && sort === "popular" ? "relevance" : sort,
  };
  const listings = useQuery(marketplaceQuery(params));

  return (
    <div className="mx-auto grid max-w-6xl gap-6">
      <div>
        <h1 className="text-2xl font-semibold">Marketplace</h1>
        <p className="text-sm text-muted-foreground">
          Flows shared by other people and starter templates for common forms. Shared flows never include anyone&apos;s default values.
        </p>
      </div>

      <div className="flex flex-wrap gap-2" role="tablist">
        {TABS.map((t) => (
          <button
            key={t.id}
            role="tab"
            aria-selected={tab === t.id}
            onClick={() => setTab(t.id)}
            className={`flex items-center gap-2 rounded-md px-3 py-2 text-sm ${tab === t.id ? "bg-secondary font-medium" : "text-muted-foreground hover:bg-secondary/60"}`}
          >
            <t.icon className="size-4" /> {t.label}
          </button>
        ))}
      </div>

      <div className="flex flex-col gap-2 sm:flex-row">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute top-2.5 left-3 size-4 text-muted-foreground" />
          <Input aria-label="Search" className="pl-9" placeholder="Search by app, form or words…" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <NativeSelect aria-label="Category" className="sm:w-48" value={category} onChange={(e) => setCategory(e.target.value)}>
          <option value="">All categories</option>
          {categories.data?.map((c) => (
            <option key={c} value={c}>
              {humanizeCategory(c)}
            </option>
          ))}
        </NativeSelect>
        <NativeSelect aria-label="Sort" className="sm:w-44" value={sort} onChange={(e) => setSort(e.target.value as typeof sort)}>
          <option value="popular">Most imported</option>
          <option value="rating">Best rated</option>
          <option value="recent">Recently updated</option>
        </NativeSelect>
      </div>

      {listings.isPending ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-40" />
          ))}
        </div>
      ) : listings.data?.items.length ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {listings.data.items.map((l) => (
            <ListingCard key={l.id} listing={l} />
          ))}
        </div>
      ) : (
        <Card>
          <CardContent className="p-6 text-sm text-muted-foreground">
            {tab === "mine" ? "You haven't published any flows yet. Open a flow and choose “Publish”." : "Nothing found. Try other words or another category."}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

export function Stars({ value, count }: { value: number | null | undefined; count: number }) {
  if (!count || value == null) return <span className="text-xs text-muted-foreground">No ratings yet</span>;
  return (
    <span className="flex items-center gap-1 text-xs" aria-label={`${value} out of 5 stars from ${count} ratings`}>
      <Star className="size-3.5 fill-current text-amber-500" /> {value.toFixed(1)} <span className="text-muted-foreground">({count})</span>
    </span>
  );
}

function ListingCard({ listing }: { listing: Listing }) {
  return (
    <Link to="/marketplace/$listingId" params={{ listingId: listing.id }} className="group">
      <Card className="h-full transition-shadow group-hover:shadow-md">
        <CardHeader>
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant="secondary">{humanizeCategory(listing.category)}</Badge>
            {listing.isTemplate && <Badge>Template</Badge>}
            {listing.mine && <Badge variant="outline">Yours</Badge>}
          </div>
          <CardTitle className="text-base">{listing.name}</CardTitle>
          <CardDescription className="line-clamp-2">{listing.description || "No description."}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
          {!listing.isTemplate && <span className="font-mono">{listing.appPackage}</span>}
          <Stars value={listing.ratingAverage} count={listing.ratingCount} />
          {!listing.isTemplate && (
            <span className="flex items-center gap-1">
              <Download className="size-3.5" /> {listing.installCount}
            </span>
          )}
          <span>v{listing.latestVersion}</span>
          {listing.ownerName && <span>by {listing.ownerName}</span>}
        </CardContent>
      </Card>
    </Link>
  );
}
