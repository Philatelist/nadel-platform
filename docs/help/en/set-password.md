---
id: set-password
title: "Set a password"
description: "The page opened from the invitation letter: how to set a password, the password rules, and what to do when the link is not valid."
kind: topic
routes: []
permissions: []
section: getting-started
order: 7
shots: []
---

This page opens from the link in the letter "{{ui:setPassword.title}}" that the board sends when it invites you to the system (page address: `/set-password`). You do not need to be signed in.

## What you see

First the system checks the link: "{{ui:setPassword.loading}}" Then the page shows your login, which is your e-mail: "{{ui:setPassword.email}}". Below are two fields: "{{ui:setPassword.password}}" and "{{ui:setPassword.confirm}}".

## Password rules

- "{{ui:setPassword.hintLength}}";
- the password is typed twice and both values must match: when they are the same, the system says "{{ui:setPassword.hintMatch}}".

Press "{{ui:setPassword.submit}}". When the password is saved, the system says "{{ui:setPassword.done}}", "{{ui:setPassword.doneText}}", and the "{{ui:setPassword.signIn}}" button leads to the sign-in page. Sign in with your e-mail and the new password.

## If the link does not work

The link is valid for 72 hours and works once. If it has expired, was already used or the board sent a newer one, the system says: "{{ui:setPassword.invalid}}" Ask the board to press "{{ui:memberAccess.resend}}" on your [member card](help:member-card).

A working link can be opened as often as you like. After 5 wrong or expired links within 15 minutes from one computer the page says: "{{ui:problem.TOO_MANY_REQUESTS}}" Your link is not spoiled by this: wait 15 minutes and open it again.

## Related pages

- [Getting started for a member](help:member-getting-started)
- [Member card](help:member-card)
