workspace extends workspace.json {

    model {
        // !ref with DSL identifier
        !ref softwareSystem1 {
            webapp1 = container "Web Application 1"
        }

        // !ref with canonical name
        !ref "SoftwareSystem://Software System 1" {
            webapp2 = container "Web Application 2"
        }

        user -> softwareSystem1 "Uses"
        softwareSystem3.webapp -> softwareSystem3.db
    }

}