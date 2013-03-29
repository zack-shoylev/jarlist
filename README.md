jarlist
=======

analyze large java projects with reflection - work in progress

sample command line: (--help for descriptions)

--dir ~/big_project --path-filter .*openstack.* --class-filter (?i).*api.* --method-group (?i).*update.* --method-group (?i).*delete.* --method-group (?i).*create.* --method-group (?i).*init.* --libs ~/maven_repository --method-filter-exclude (?i).*test.* --method-filter-include .*jclouds.*
