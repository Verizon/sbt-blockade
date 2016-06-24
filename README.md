# sbt-dependency-sieve
> Defend your builds from bad deps!

[![Build Status](https://travis.oncue.verizon.net/iptv/sbt-dependency-sieve.svg?token=Lp2ZVD96vfT8T599xRfV)](https://travis.oncue.verizon.net/iptv/sbt-dependency-sieve)

## Overview

`sbt-dependency-sieve` gives you the ability to restrict which dependencies are tolerated in your builds.

Specify your restricted and allowed dependecies using plain ol' JSON.

`sbt-dependency-sieve` allows you to specify whitelisted packages, or blacklisted packages -- or both! See [Specifying dependency restrictions](### Specifying dependency restrictions).


## Getting Started

Both a whitelist and blacklist may be used. Ivy version ranges are specified in accordance with [the Ivy version matcher docs](http://ant.apache.org/ivy/history/2.1.0/settings/version-matchers.html).

### Specifying dependency restrictions

Dependency restrictions are specified using a JSON object containing a (possibly empty) array of blacklisted items and a (possibly empty) array of whitelisted items. Here's an example:

```json
{
  "whitelist": [
    {
      "organization": "commons-codec",
      "name": "commons-codec",
      "range": "[2.0,3.0["
    },
  ],
  "blacklist": [
    {
      "organization": "commons-codec",
      "name": "commons-codec",
      "range": "[1.0,1.6]",
      "expiry": "2017-02-01 11:59:59"
    },
    {
      "organization": "commons-io",
      "name": "commons-io",
      "range": "(,2.4[",
      "expiry": "2017-02-02 11:59:59"
    },
    {
      "organization": "commons-net",
      "name": "commons-net",
      "range": "(,3.0[",
      "expiry": "2017-02-03 11:59:59"
    },
    {
      "organization": "commons-lang",
      "name": "commons-lang",
      "range": "[1.0,2.3]",
      "expiry": "2017-02-04 11:59:59"
    }
  ]
}
```

Note that a package can be a member of both a whitelist and a blacklist. And a package can be entered more than once (with differing restrictions) on a blacklist. Similarly, a whitelist may have more than one entry for a particular package.

Blacklists have *OR* semantics. In other words, the *union* of blacklist constaints is enforced -- a package meeting *any* blacklist constraint is restricted.

Whitelists have *AND* semantics. A package must meet the *all* whitelist constraints to not be restricted.

#### Blacklist items
Here's an example blacklist item:

```json
{
  "organization": "commons-codec",
  "name": "commons-codec",
  "range": "[1.2.+,)",
  "expiry": "2017-02-01 11:59:59"
}
```
The `range` field is specified using [Ivy Version Matchers](http://ant.apache.org/ivy/history/2.1.0/settings/version-matchers.html).

#### Whitelist items
Whitelist items are similar to blacklist items, except that they are effective immediately, so they have no `expiry` field. Here's an example whitelist item:

```json
{
  "range": "[7.1.0, 7.2.0[",
  "name": "scalaz-core",
  "organization": "org.scalaz"
}

```
The `range` field is specified using [Ivy Version Matchers](http://ant.apache.org/ivy/history/2.1.0/settings/version-matchers.html).