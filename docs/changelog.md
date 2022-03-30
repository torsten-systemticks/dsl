# Changelog

## 1.19.1 (30th March 2022)

- `title` now works for custom views.
- Adds `description` to set view descriptions.

## 1.19.0 (23rd March 2022)

- Uses the new documentation/ADR importers, and makes it possible to use a custom implementation via `!docs` and `!adrs`.

## 1.18.0 (20th February 2022)

- Removes backwards compatibility for unquoted expressions (e.g. `exclude src -> dest` - use `exclude "src -> dest"` instead).
- Adds the ability to include/exclude relationships by the tag of source of destination elements (e.g. `exclude "* -> element.tag==Tag"`).
- Adds support for directory based includes with `!include`.
- Adds support for element expressions to be used in the afferent/efferent coupling expression (e.g. `exclude "->element.tag==Tag"`)
- Adds support for element expressions of the form `element.parent==<identifier>`.
- Fixes #113 (Excluding relationships with tags in a Deployment Diagram).

## 1.17.0 (4th January 2022)

- Adds support for extending deployment environments via the `!ref` keyword (issue #92).
- Adds support for extending relationships via the `!ref` keyword (issue #93).
- Fixes #94 (tabs cause parsing errors).
- Adds support for different relationship line styles (solid, dashed, dotted).
- Adds support for name/value properties on the view set.

## 1.16.0 (26th November 2021)

- Adds the implied relationships functionality for custom elements.
- The "add default elements" feature (`include *`) will now also add any connected custom elements.
- Adds better support for custom elements when using element expressions.
- Adds a `description` keyword for setting the description on elements.
- Adds a `technology` keyword for setting the technology on containers, components, deployment nodes, and infrastructure nodes.

## 1.15.0 (2nd October 2021)

- Adds support for specifying element style icons and the branding logo as a HTTPS/HTTP URL.
- Adds support for relationships from deployment nodes to infrastructure nodes.
- Fixes an issue where `this` didn't work when defining relationships inside deployment/infrastructure nodes.
- Removes the restriction that `include *` must be on a line of its own inside view definitions.

## 1.14.0 (19th September 2021)

- __Breaking change__: Adds support for software system/container instances in multiple deployment groups.
- Fixes an issue where internal software systems/people are not included by the DSL formatter when the enterprise is not set.
- The DSL formatter now removes empty tokens at the end of lines.
- Adds support for recognising escaped newlines (\n) in tokens.

## 1.13.0 (3rd September 2021)

- __Breaking change__: `impliedRelationships` is now `!impliedRelationships`.
- Adds support for "include relationship==*" (#68).
- Fixes #69 (hierarchical identifiers not working at the top level inside a deployment environment).
- Adds experimental support for `!ref`, `!plugin`, and `!script`.

## 1.12.0 (30th June 2021)

- Adds an `!identifiers` keyword to specify whether element identifiers should be `flat` (default) or `hierarchical`.
- Adds support for a `this` identifier when defining relationships inside element definitions.
- Fixes links between ADRs.

## 1.11.0 (7th June 2021)

- __Breaking change__: Default styles are no longer added; use the `default` theme to add some default styles instead.
- __Breaking change__: Relationship expressions (e.g. * -> *) now need to be surrounded in double quotes.
- Support parallel activities in dynamic view (issue #53).
- Adds a `tags` keyword for adding tags to elements/relationships.
- Adds a `theme` keyword for adding a single theme.
- Adds support to `!include` from a HTTPS URL.
- Adds support for referencing groups by identifier.
- Adds support for extending a workspace.

## 1.10.0 (27th April 2021)

- First version released onto Maven Central.