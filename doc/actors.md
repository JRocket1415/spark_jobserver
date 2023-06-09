<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->
**Table of Contents**  *generated with [DocToc](https://github.com/thlorenz/doctoc)*

- [Introduction](#introduction)
- [JobServer ActorSystem](#jobserver-actorsystem)
  - [WebApi](#webapi)
  - [ContextSupervisor](#contextsupervisor)
- [JobContext ActorSystem](#jobcontext-actorsystem)
  - [JobManager](#jobmanager)
  - [AdHocJobManager](#adhocjobmanager)
  - [JobStatusActor](#jobstatusactor)
  - [JobDAOActor](#jobdaoactor)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

# Introduction

There are two separate ActorSystems or clusters of actors in the job server architecture.

* JobServer system - this contains the actual REST API and manages all of the job context systems.
* JobContext system - one system per JobContext.

Each JobContext could potentially run in its own process for isolation purposes, and multiple JobServers may connect to the same JobContext for HA.

# JobServer ActorSystem

Here are all the actors for the job server.

## WebApi

This is not really an actor but contains the web routes.

## ContextSupervisor

- Creates and stops JobContext actorsystems
- Sends jobs on to job contexts
- Is a singleton

# JobContext ActorSystem

## JobManager

This was the "ContextManager" actor.

- One per context
- Starts JobActors for every job in the context
- Returns an error if there are no more threads for jobs or capacity is full
- Starts and supervises the JobStatus and JobResult actors

## AdHocJobManager

A special JobManager for running ad-hoc jobs, which require temporary per-job JobContexts.

- When the job terminates, the JobManager cleans up the SparkContext.

## JobStatusActor

- One per JobManager
- Collects and persists job status and progress updates (including exceptions) from every job in JobManager
    - JDBC updates
    - log file
    - WebSocket?
- Handles subscriptions from external actors for listening to status updates for specific jobID's
- Watches the JobActors, removing subscriptions once the actor terminates

## JobDAOActor

- One per each JobManageActor and WebApi
- Accesses binary and metadata storage and persists data
