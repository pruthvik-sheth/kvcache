# gossip

Epidemic-style cluster membership and failure detection.

## Responsibility

- Maintains each node's local view of which cluster members are alive.
- Periodically fans out to random peers to exchange membership state.
- Publishes `NodeJoined` / `NodeSuspected` / `NodeLeft` events to the EventBus.

## Protocol

SWIM-inspired: each gossip round, a node pings a random peer. On timeout,
it indirect-pings via K other nodes before marking the peer as suspected.

## Package Layout

```
api/        GossipService interface, MembershipView
impl/       SwimGossipService
model/      Member, MemberState, GossipMessage
exception/  GossipException
```

## References

- SWIM paper: "SWIM: Scalable Weakly-consistent Infection-style Process Group
  Membership Protocol" (Das, Gupta, Motivala — 2002)
